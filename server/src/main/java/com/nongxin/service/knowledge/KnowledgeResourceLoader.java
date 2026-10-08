package com.nongxin.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.knowledge.KbData;
import com.nongxin.domain.knowledge.KbEntry;
import com.nongxin.domain.knowledge.KnowledgeBundle;
import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verified source loading and explicitly unverified draft conversion. */
public final class KnowledgeResourceLoader {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeResourceLoader.class);
    private final List<KnowledgeDocument> documents = new ArrayList<>();
    private final List<KnowledgeChunk> chunks = new ArrayList<>();
    private final Map<String, KnowledgeChunk> chunksById = new LinkedHashMap<>();
    private final Map<String, KnowledgeDocument> documentsById = new LinkedHashMap<>();

    private KnowledgeResourceLoader() {}

    public record Catalog(
            List<KnowledgeDocument> documents,
            List<KnowledgeChunk> chunks,
            Map<String, KnowledgeDocument> documentsById,
            Map<String, KnowledgeChunk> chunksById) {}

    public static Catalog load(
            Resource draftResource, Resource sourcesResource, ObjectMapper json) {
        var loader = new KnowledgeResourceLoader();
        loader.loadSources(sourcesResource, json);
        loader.loadDrafts(draftResource, json);
        return new Catalog(
                loader.documents, loader.chunks, loader.documentsById, loader.chunksById);
    }

    private void loadSources(Resource resource, ObjectMapper json) {
        try {
            KnowledgeBundle bundle =
                    json.readValue(resource.getInputStream(), KnowledgeBundle.class);
            if (bundle == null) return;
            if (bundle.documents() != null)
                for (KnowledgeDocument document : bundle.documents()) register(document);
            if (bundle.chunks() != null)
                for (KnowledgeChunk chunk : bundle.chunks()) {
                    if (!documentsById.containsKey(chunk.documentId())) {
                        throw new IllegalStateException(
                                "片段 " + chunk.id() + " 指向不存在的文档 " + chunk.documentId());
                    }
                    if (chunksById.containsKey(chunk.id())) {
                        throw new IllegalStateException("片段 ID 重复：" + chunk.id());
                    }
                    chunks.add(chunk);
                    chunksById.put(chunk.id(), chunk);
                }
        } catch (Exception e) {
            log.error("来源资料加载失败：{}", e.getMessage());
            throw new IllegalStateException("来源资料无法加载", e);
        }
    }

    /** 本地草稿统一登记为 unverified：没有 URL 与原文核验，绝不能宣称已核实。 */
    private void loadDrafts(Resource resource, ObjectMapper json) {
        try {
            KbData data = json.readValue(resource.getInputStream(), KbData.class);
            if (data == null || data.entries() == null) return;
            for (KbEntry entry : data.entries()) {
                String docId = "doc-draft-" + entry.id();
                register(
                        new KnowledgeDocument(
                                docId,
                                entry.title(),
                                "本地整理草稿（未核验）",
                                null,
                                null,
                                null,
                                null,
                                List.of(entry.crop()),
                                entry.topic(),
                                null,
                                null,
                                "unverified",
                                "本地整理条目，来源名称尚未逐条核验原文；不得据此宣称官方已确认或现行登记。"));
                KnowledgeChunk chunk =
                        new KnowledgeChunk(
                                "chunk-draft-" + entry.id(),
                                docId,
                                entry.title(),
                                "本地整理条目（无原文定位）",
                                entry.crop(),
                                null,
                                null,
                                entry.topic(),
                                draftText(entry),
                                entry.keywords() == null ? List.of() : entry.keywords());
                chunks.add(chunk);
                chunksById.put(chunk.id(), chunk);
            }
        } catch (Exception e) {
            log.error("本地草稿加载失败：{}", e.getMessage());
        }
    }

    private String draftText(KbEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.symptom() != null && !entry.symptom().isBlank())
            sb.append("症状：").append(entry.symptom()).append('\n');
        if (entry.diagnosis() != null && !entry.diagnosis().isBlank())
            sb.append("判断：").append(entry.diagnosis()).append('\n');
        if (entry.advice() != null && !entry.advice().isEmpty()) {
            sb.append("处置：");
            for (int i = 0; i < entry.advice().size(); i++)
                sb.append(i + 1).append(". ").append(entry.advice().get(i)).append('；');
            sb.append('\n');
        }
        if (entry.review() != null && !entry.review().isBlank())
            sb.append("复查：").append(entry.review()).append('\n');
        if (entry.source() != null && !entry.source().isBlank())
            sb.append("草稿标注来源：").append(entry.source());
        return sb.toString().trim();
    }

    private void register(KnowledgeDocument document) {
        if (documentsById.containsKey(document.id())) {
            throw new IllegalStateException("来源文档 ID 重复：" + document.id());
        }
        documents.add(document);
        documentsById.put(document.id(), document);
    }
}
