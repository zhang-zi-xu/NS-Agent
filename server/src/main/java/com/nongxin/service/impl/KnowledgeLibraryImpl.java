package com.nongxin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;
import com.nongxin.domain.knowledge.KnowledgeRelation;
import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;
import com.nongxin.service.knowledge.AgriculturalEvidenceRanker;
import com.nongxin.service.knowledge.AgriculturalLexicalRetrieval;
import com.nongxin.service.knowledge.AgriculturalQuery;
import com.nongxin.service.knowledge.KeywordRetrieval;
import com.nongxin.service.knowledge.KnowledgeApplicability;
import com.nongxin.service.knowledge.KnowledgeResourceLoader;
import com.nongxin.service.knowledge.ReciprocalRankFusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source registry + local drafts; lexical/graph recall with optional vectors and applicability
 * reranking.
 */
@Service
public class KnowledgeLibraryImpl implements KnowledgeLibrary {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLibraryImpl.class);

    private final List<KnowledgeDocument> documents = new ArrayList<>();
    private final List<KnowledgeChunk> chunks = new ArrayList<>();
    private final Map<String, KnowledgeChunk> chunksById = new LinkedHashMap<>();
    private final Map<String, KnowledgeDocument> documentsById = new LinkedHashMap<>();
    private final KeywordRetrieval keyword = new KeywordRetrieval(documentsById);
    private final AgriculturalLexicalRetrieval lexical =
            new AgriculturalLexicalRetrieval(documentsById);

    /** Optional vector recall; local lexical and graph recall do not depend on its availability. */
    private final EmbeddingService embeddingService;

    private final VectorIndexService vectorIndexService;
    private final int rrfK;
    private final int vectorTopK;

    /** 来源约束图谱实体召回；为 null 时该路自动跳过。 */
    private final KnowledgeGraphService graphService;

    private final int graphTopK;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeLibraryImpl(
            @Value("${nongxin.kb-resource}") Resource draftResource,
            @Value("${nongxin.sources-resource:classpath:knowledge/sources.json}")
                    Resource sourcesResource,
            @Value("${nongxin.retrieval.rrf-k:60}") int rrfK,
            @Value("${nongxin.retrieval.vector-top-k:12}") int vectorTopK,
            ObjectMapper objectMapper,
            ObjectProvider<EmbeddingService> embeddingProvider,
            ObjectProvider<VectorIndexService> vectorIndexProvider,
            ObjectProvider<KnowledgeGraphService> graphProvider,
            @Value("${nongxin.retrieval.graph-top-k:8}") int graphTopK) {
        this.embeddingService =
                embeddingProvider == null ? null : embeddingProvider.getIfAvailable();
        this.vectorIndexService =
                vectorIndexProvider == null ? null : vectorIndexProvider.getIfAvailable();
        // 图谱 bean 在 ApplicationReadyEvent 中构图，这里只保留引用（延迟调用）
        this.graphService = graphProvider == null ? null : graphProvider.getIfAvailable();
        this.graphTopK = Math.max(1, graphTopK);
        this.rrfK = Math.max(1, rrfK);
        this.vectorTopK = Math.max(1, vectorTopK);
        var catalog = KnowledgeResourceLoader.load(draftResource, sourcesResource, objectMapper);
        documents.addAll(catalog.documents());
        chunks.addAll(catalog.chunks());
        documentsById.putAll(catalog.documentsById());
        chunksById.putAll(catalog.chunksById());
        log.info(
                "资料库加载完成：{} 篇来源文档（已核验 {} 篇）、{} 条片段；混合检索={}",
                documents.size(),
                documents.stream().filter(KnowledgeDocument::verified).count(),
                chunks.size(),
                embeddingService != null && embeddingService.available()
                        ? "词法 + 图谱 + 可选向量(RRF)"
                        : "词法 + 图谱（向量未启用）");
    }

    /**
     * Test/embedded constructor: local lexical retrieval; Spring supplies the optional
     * collaborators.
     */
    public KnowledgeLibraryImpl(
            Resource draftResource, Resource sourcesResource, ObjectMapper objectMapper) {
        this(draftResource, sourcesResource, 60, 12, objectMapper, null, null, null, 8);
    }

    @Override
    public List<KnowledgeDocument> documents() {
        return documents.stream()
                .sorted(
                        Comparator.comparing((KnowledgeDocument d) -> d.verified() ? 0 : 1)
                                .thenComparing(KnowledgeDocument::id))
                .toList();
    }

    @Override
    public List<KnowledgeChunk> chunks() {
        return List.copyOf(chunks);
    }

    @Override
    public List<SourcedHit> search(String query, String crop, String region, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        Set<String> terms = AgriculturalQuery.terms(query);
        if (terms.isEmpty()) return List.of();
        String cropFinal = crop == null || crop.isBlank() ? detectCrop(query) : crop.trim();
        String regionFinal = region == null || region.isBlank() ? null : region.trim();

        // 候选池：作物硬过滤（水稻问题绝不能拿小麦资料当依据），两条召回路径共用
        List<KnowledgeChunk> pool =
                chunks.stream()
                        .filter(
                                chunk ->
                                        KnowledgeApplicability.supports(
                                                chunk,
                                                documentsById.get(chunk.documentId()),
                                                cropFinal))
                        .filter(
                                chunk ->
                                        !AgriculturalQuery.isPreparation(query)
                                                || AgriculturalQuery.supportsPreparation(
                                                        chunk.heading(),
                                                        chunk.growthStage(),
                                                        chunk.text()))
                        .toList();

        // ---- 路 A：词法检索，要求标题/关键词或已登记农技词锚点，防止正文偶然命中 ----
        List<SourcedHit> keywordHits = lexical.rank(pool, query, regionFinal);

        // ---- 向量为可选路径；不可用时不能跳过词法、图谱及适用性排序 ----
        boolean vectorUsable =
                embeddingService != null
                        && embeddingService.available()
                        && vectorIndexService != null
                        && vectorIndexService.ready();

        // ---- 路 B：向量语义检索（可召回"叶子像开水烫过"这类无字面重合的口语描述）----
        List<VectorIndexService.Scored> vectorHits = List.of();
        float[] queryVector = vectorUsable ? embeddingService.embed(query) : null;
        if (queryVector != null) {
            Map<String, KnowledgeChunk> poolById = new LinkedHashMap<>();
            for (KnowledgeChunk chunk : pool) poolById.put(chunk.id(), chunk);
            vectorHits =
                    vectorIndexService.search(queryVector, Math.min(100, vectorTopK * 4)).stream()
                            .filter(scored -> poolById.containsKey(scored.chunkId()))
                            .limit(vectorTopK)
                            .toList();
        }

        // ---- 路 C：来源约束图谱实体召回，不进行同文档无条件扩展 ----
        List<String> graphHits = List.of();
        if (graphService != null) {
            Set<String> already = new LinkedHashSet<>();
            for (SourcedHit hit : keywordHits.stream().limit(3).toList())
                already.add(hit.chunk().id());
            for (VectorIndexService.Scored scored : vectorHits) already.add(scored.chunkId());
            Map<String, KnowledgeChunk> poolIndex = new LinkedHashMap<>();
            for (KnowledgeChunk chunk : pool) poolIndex.put(chunk.id(), chunk);
            graphHits =
                    graphService.expand(query, cropFinal, already, graphTopK).stream()
                            .filter(poolIndex::containsKey)
                            .toList();
        }

        List<SourcedHit> candidates =
                ReciprocalRankFusion.fuse(
                        keywordHits.stream().limit(Math.max(vectorTopK, topK)).toList(),
                        vectorHits,
                        graphHits,
                        pool,
                        documentsById,
                        rrfK,
                        Math.max(topK, vectorTopK + graphTopK));
        return AgriculturalEvidenceRanker.rank(candidates, keywordHits, query, regionFinal, topK);
    }

    @Override
    public List<SourcedHit> searchKeywordOnly(String query, String crop, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        Set<String> terms = keyword.terms(query);
        if (terms.isEmpty()) return List.of();
        String cropFinal = crop == null || crop.isBlank() ? null : crop.trim();
        List<KnowledgeChunk> pool =
                chunks.stream()
                        .filter(
                                chunk ->
                                        cropFinal == null
                                                || chunk.crop() == null
                                                || chunk.crop().isBlank()
                                                || "通用".equals(chunk.crop())
                                                || cropFinal.equals(chunk.crop()))
                        .toList();
        return keyword.rank(pool, terms, null).stream().limit(topK).toList();
    }

    @Override
    public String formatForModel(List<SourcedHit> hits) {
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(
                "【检索候选资料】召回不代表适用或已经引用；核对原文支持的具体操作与条件后才能引用。只能引用下面列出的来源ID；不得引用未列出的来源，也不得凭记忆补充剂量或登记信息。\n");
        for (int i = 0; i < hits.size(); i++) {
            SourcedHit hit = hits.get(i);
            KnowledgeChunk chunk = hit.chunk();
            KnowledgeDocument document = hit.document();
            sb.append("\n[")
                    .append(i + 1)
                    .append("] 来源ID：")
                    .append(chunk.id())
                    .append("（")
                    .append(document != null && document.verified() ? "已核验原文" : "本地草稿·未核验原文")
                    .append("）\n");
            if (document != null) {
                sb.append("标题：")
                        .append(document.title())
                        .append('\n')
                        .append("机构：")
                        .append(document.institution() == null ? "未登记" : document.institution())
                        .append('\n')
                        .append("发布日期：")
                        .append(document.publishedAt() == null ? "未登记" : document.publishedAt())
                        .append('\n')
                        .append("原文链接：")
                        .append(document.url() == null ? "无（未核验草稿）" : document.url())
                        .append('\n');
            }
            sb.append("适用作物：")
                    .append(orUnknown(chunk.crop()))
                    .append("；适用地区：")
                    .append(orUnknown(chunk.region()))
                    .append("；生育期：")
                    .append(orUnknown(chunk.growthStage()))
                    .append('\n')
                    .append("定位：")
                    .append(orUnknown(chunk.heading()))
                    .append(" · ")
                    .append(orUnknown(chunk.locator()))
                    .append('\n')
                    .append("原文摘录：")
                    .append(chunk.text())
                    .append('\n');
        }
        if (graphService != null) {
            Set<String> ids =
                    hits.stream()
                            .map(h -> h.chunk().id())
                            .collect(java.util.stream.Collectors.toSet());
            List<KnowledgeRelation> relations = graphService.relations(ids, null);
            if (relations != null && !relations.isEmpty()) {
                sb.append("\n【原文支持的农业关系｜不是本田块诊断】\n");
                for (KnowledgeRelation relation : relations.stream().limit(12).toList()) {
                    sb.append(relation.crop())
                            .append(" · ")
                            .append(relation.subject())
                            .append(" — ")
                            .append(relation.predicate())
                            .append(" → ")
                            .append(relation.object())
                            .append("；来源ID：")
                            .append(relation.sourceId())
                            .append("；关系定位原句：")
                            .append(relation.evidenceQuote())
                            .append('\n');
                }
                boolean different =
                        relations.stream()
                                .anyMatch(
                                        a ->
                                                relations.stream()
                                                        .anyMatch(
                                                                b ->
                                                                        !a.sourceId()
                                                                                        .equals(
                                                                                                b
                                                                                                        .sourceId())
                                                                                && a.crop()
                                                                                        .equals(
                                                                                                b
                                                                                                        .crop())
                                                                                && a.subject()
                                                                                        .equals(
                                                                                                b
                                                                                                        .subject())
                                                                                && a.predicate()
                                                                                        .equals(
                                                                                                b
                                                                                                        .predicate())
                                                                                && !a.object()
                                                                                        .equals(
                                                                                                b
                                                                                                        .object())));
                if (different)
                    sb.append(
                            "资料口径提示：同主题关系在不同来源中表述不同，可能涉及年份、地区或适用条件差异。逐条保留条件与出处，不拼接、平均或直接替换成一个通用数值；不能自行判断哪条现行有效。\n");
            }
        }
        return sb.toString();
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "未标注" : value;
    }

    @Override
    public List<SourcedHit> resolve(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        List<SourcedHit> out = new ArrayList<>();
        for (String id : chunkIds) {
            if (id == null || id.isBlank()) continue;
            KnowledgeChunk chunk = chunksById.get(id.trim());
            if (chunk != null)
                out.add(new SourcedHit(chunk, documentsById.get(chunk.documentId()), 0));
        }
        return out;
    }

    @Override
    public String detectCrop(String query) {
        if (query == null || query.isBlank()) return null;
        String focused = AgriculturalQuery.crop(query);
        if (focused != null) return focused;
        if (AgriculturalQuery.normalize(query).contains("水稻")
                && AgriculturalQuery.normalize(query).contains("小麦")) return null;
        for (KnowledgeChunk chunk : chunks) {
            String crop = chunk.crop();
            if (crop == null || crop.isBlank() || "通用".equals(crop)) continue;
            if (query.contains(crop.trim())) return crop.trim();
        }
        return null;
    }

    @Override
    public String retrievalGuidance(
            List<SourcedHit> hits, String query, String crop, String region) {
        StringBuilder out = new StringBuilder("\n【检索适用性核查】召回相关资料不等于确认病因，也不等于建议适用于用户田块。\n");
        if (crop == null) out.append("作物未明确：不同作物的材料不能混用；涉及具体行动时先确认作物。\n");
        if (region == null) out.append("未提供可匹配地区：地方资料只供参考，不假设用户在该地区。\n");
        String normalized = AgriculturalQuery.normalize(query);
        for (SourcedHit hit : hits) {
            if (hit.document() == null || !hit.document().verified())
                out.append(hit.chunk().id()).append(" 是未核验线索，不能作为具体处方的依据。\n");
            if (region != null && !KnowledgeLibrary.regionUsable(region, hit.chunk().region()))
                out.append(hit.chunk().id()).append(" 的地区未匹配，不能直接套用地方时限、阈值或作业条件。\n");
            String stage = hit.chunk().growthStage();
            if (stage == null || stage.isBlank())
                out.append(hit.chunk().id()).append(" 未单独标注生育期，需从原文核对适用阶段。\n");
            else if (!"全生育期".equals(stage)
                    && AgriculturalQuery.STAGES.stream()
                            .anyMatch(s -> normalized.contains(s) && !stage.contains(s)))
                out.append(hit.chunk().id()).append(" 的生育期标签未明确覆盖问题中的全部阶段，核对原文而非自动判为适用或不适用。\n");
        }
        return out.toString();
    }

    /** 来源卡由后端从资料库生成：状态与链接都不接受客户端输入。 */
    @Override
    public List<Map<String, Object>> cards(Collection<String> chunkIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourcedHit hit : resolve(chunkIds)) {
            KnowledgeChunk chunk = hit.chunk();
            KnowledgeDocument document = hit.document();
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", chunk.id());
            card.put("title", document == null ? "未登记来源" : document.title());
            card.put("institution", document == null ? null : document.institution());
            card.put("url", document == null ? null : document.url());
            card.put("publishedAt", document == null ? null : document.publishedAt());
            card.put("region", chunk.region());
            card.put("crop", chunk.crop());
            card.put("growthStage", chunk.growthStage());
            card.put("heading", chunk.heading());
            card.put("status", document != null && document.verified() ? "verified" : "unverified");
            card.put("excerpt", excerpt(chunk.text()));
            out.add(card);
        }
        return out;
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
