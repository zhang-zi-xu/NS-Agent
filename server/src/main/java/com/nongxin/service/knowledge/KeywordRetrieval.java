package com.nongxin.service.knowledge;

import static com.nongxin.service.KnowledgeLibrary.REGION_ALIASES;

import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;
import com.nongxin.service.KnowledgeLibrary.SourcedHit;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keyword expansion and agricultural-source ranking; no persistence or model requests. */
public final class KeywordRetrieval {
    private static final List<String[]> SYNONYMS =
            List.of(
                    new String[] {"稻瘟病", "稻瘟", "叶瘟", "穗颈瘟"},
                    new String[] {"纹枯病", "纹枯", "云纹"},
                    new String[] {"飞虱", "稻飞虱", "褐飞虱", "白背飞虱", "灰飞虱"},
                    new String[] {"螟虫", "二化螟", "三化螟", "大螟", "钻心虫"},
                    new String[] {"卷叶螟", "稻纵卷叶螟", "束叶"},
                    new String[] {"稻曲病", "曲病"},
                    new String[] {"赤霉病", "赤霉", "穗腐"},
                    new String[] {"锈病", "条锈", "叶锈"},
                    new String[] {"蚜虫", "麦蚜", "穗蚜"},
                    new String[] {"干热风", "高温逼熟"},
                    new String[] {"倒伏", "抗倒", "控旺"},
                    new String[] {"涝", "积水", "洪涝", "渍", "梅涝"},
                    new String[] {"晒田", "烤田", "控蘖"},
                    new String[] {"药害", "烧叶", "畸形"},
                    new String[] {"高温", "热害", "花粉败育"},
                    new String[] {"磷酸二氢钾", "叶面肥", "叶面喷施"},
                    new String[] {"穗肥", "追肥", "施肥"},
                    new String[] {"破口", "抽穗", "齐穗"});

    /** 泛化 2-gram 会造成"柑橘黄龙病防治"命中一切含"防治"的片段，检索前先剔除。 */
    private static final Set<String> STOP_TERMS =
            Set.of(
                    "防治", "防控", "管理", "技术", "措施", "方法", "注意", "工作", "情况", "发生", "进行", "加强", "做好",
                    "及时", "各地", "要点", "指导", "意见", "如何", "怎么", "什么", "时候", "需要", "可以", "是否", "怎样",
                    "为什么", "要求", "建议");

    private final Map<String, KnowledgeDocument> documentsById;

    public KeywordRetrieval(Map<String, KnowledgeDocument> documentsById) {
        this.documentsById = documentsById;
    }

    public Set<String> terms(String query) {
        return expandTerms(ngrams(query));
    }

    /** 关键词路排序（标题/关键词级命中约束 + 加权分）：抽成独立方法便于评估对照与复用。 */
    public List<SourcedHit> rank(List<KnowledgeChunk> pool, Set<String> terms, String regionFinal) {
        return pool.stream()
                .filter(chunk -> headlineHit(chunk, terms))
                .map(
                        chunk ->
                                new SourcedHit(
                                        chunk,
                                        documentsById.get(chunk.documentId()),
                                        weightedScore(chunk, terms, regionFinal)))
                .filter(hit -> hit.score() > 0)
                .sorted(
                        Comparator.comparingInt(SourcedHit::score)
                                .reversed()
                                .thenComparing(hit -> hit.chunk().id()))
                .toList();
    }

    /**
     * 加权分：相关度 + 已核验原文优先（+4）+ 与请求地区同区优先（+4）。 地区不做硬过滤——同一条资料常跨省适用，硬过滤会让结果随"是否勾选天气"而变化；
     * 改为加权排序，并在来源卡与模型文本里标注适用地区。
     */
    private int weightedScore(KnowledgeChunk chunk, Set<String> terms, String requestRegion) {
        int score = score(chunk, terms);
        if (score <= 0) return 0;
        KnowledgeDocument document = documentsById.get(chunk.documentId());
        if (document != null && document.verified()) score += 4;
        score += regionAffinity(requestRegion, chunk.region());
        return score;
    }

    /** 请求地区与片段地区的亲缘度：同省/同大区 4，全国性 1，其它 0。 */
    private static int regionAffinity(String requestRegion, String chunkRegion) {
        if (requestRegion == null || chunkRegion == null || chunkRegion.isBlank()) return 0;
        if (chunkRegion.contains("全国")) return 1;
        for (String alias : REGION_ALIASES.getOrDefault(requestRegion, List.of(requestRegion))) {
            if (chunkRegion.contains(alias)) return 4;
        }
        return 0;
    }

    /** 是否至少有一个检索词命中标题或关键词——纯正文命中不作为依据（作物词也不算）。 */
    private boolean headlineHit(KnowledgeChunk chunk, Set<String> terms) {
        String heading = chunk.heading() == null ? "" : chunk.heading().toLowerCase();
        List<String> keywords = chunk.keywords() == null ? List.of() : chunk.keywords();
        for (String term : terms) {
            String t = term.toLowerCase();
            if (heading.contains(t)) return true;
            if (keywords.stream()
                    .anyMatch(k -> k.toLowerCase().contains(t) || t.contains(k.toLowerCase())))
                return true;
        }
        return false;
    }

    private int score(KnowledgeChunk chunk, Set<String> terms) {
        int score = 0;
        String heading = chunk.heading() == null ? "" : chunk.heading().toLowerCase();
        String body = chunk.text() == null ? "" : chunk.text().toLowerCase();
        String crop = chunk.crop() == null ? "" : chunk.crop().toLowerCase();
        List<String> keywords = chunk.keywords() == null ? List.of() : chunk.keywords();
        for (String term : terms) {
            String t = term.toLowerCase();
            if (heading.contains(t)) score += 10;
            else if (keywords.stream()
                    .anyMatch(k -> k.toLowerCase().contains(t) || t.contains(k.toLowerCase())))
                score += 8;
            else if (!crop.isEmpty() && (crop.contains(t) || t.contains(crop))) score += 5;
            else if (body.contains(t)) score += 3;
        }
        return score;
    }

    private Set<String> ngrams(String query) {
        Set<String> out = new LinkedHashSet<>();
        String cleaned = query.replaceAll("[\\s，。？！、；：\"'“”（）【】\\-—…·%0-9a-zA-Z]", "");
        if (cleaned.length() >= 2 && !STOP_TERMS.contains(cleaned)) out.add(cleaned);
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            String gram = cleaned.substring(i, i + 2);
            if (!STOP_TERMS.contains(gram)) out.add(gram);
        }
        return out;
    }

    private Set<String> expandTerms(Set<String> terms) {
        Set<String> expanded = new LinkedHashSet<>(terms);
        for (String[] group : SYNONYMS) {
            boolean hit = false;
            for (String t : terms) {
                for (String alias : group) {
                    if (t.contains(alias) || alias.contains(t)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) break;
            }
            if (hit) expanded.addAll(List.of(group));
        }
        return expanded;
    }
}
