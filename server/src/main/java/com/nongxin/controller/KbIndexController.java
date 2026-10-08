package com.nongxin.controller;

import com.nongxin.security.KbAdminGuard;
import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 向量索引管理： - GET /api/kb/status 查看混合检索状态（片段数 / 已索引数 / 模型 / 是否就绪） - POST /api/kb/reindex
 * 仅管理员可重建索引；密钥只从服务端环境读取
 */
@RestController
@RequestMapping("/api/kb")
public class KbIndexController {

    private static final Logger log = LoggerFactory.getLogger(KbIndexController.class);

    private final KnowledgeLibrary library;
    private final EmbeddingService embedding;
    private final VectorIndexService vectorIndex;
    private final KnowledgeGraphService graph;
    private final KbAdminGuard admin;

    public KbIndexController(
            KnowledgeLibrary library,
            EmbeddingService embedding,
            VectorIndexService vectorIndex,
            KnowledgeGraphService graph,
            KbAdminGuard admin) {
        this.library = library;
        this.embedding = embedding;
        this.vectorIndex = vectorIndex;
        this.graph = graph;
        this.admin = admin;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunks", library.chunks().size());
        out.put("documents", library.documents().size());
        out.put(
                "verifiedDocuments",
                library.documents().stream().filter(d -> d.verified()).count());
        out.put("embeddingConfigured", embedding.available());
        out.put("embeddingModel", embedding.modelName());
        out.put("indexedChunks", vectorIndex.indexedCount());
        out.put("indexedModel", vectorIndex.indexedModel());
        out.put("vectorReady", vectorIndex.ready());
        out.put("mode", retrievalMode());
        out.put("graph", graph.stats());
        return out;
    }

    @PostMapping("/reindex")
    public ResponseEntity<?> reindex(
            @RequestHeader(value = "X-Nongxin-Admin-Token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {
        admin.require(token);
        Map<String, Object> payload = body == null ? Map.of() : body;
        if (payload.containsKey("apiKey"))
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "请通过服务端环境变量配置 embedding 密钥，不要在请求体传入"));
        boolean force = Boolean.TRUE.equals(payload.get("force"));
        if (!embedding.available()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "未配置 embedding 密钥：请设置环境变量 NONGXIN_EMBEDDING_KEY 并重启服务");
            return ResponseEntity.badRequest().body(err);
        }
        long start = System.currentTimeMillis();
        int written = vectorIndex.indexChunks(library.chunks(), force);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", written >= 0);
        out.put("written", written);
        out.put("indexedChunks", vectorIndex.indexedCount());
        out.put("model", embedding.modelName());
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("mode", retrievalMode());
        log.info(
                "向量索引任务结束：written={} indexed={} 用时 {}ms",
                written,
                vectorIndex.indexedCount(),
                out.get("elapsedMs"));
        return ResponseEntity.ok(out);
    }

    private String retrievalMode() {
        return embedding.available() && vectorIndex.ready()
                ? "hybrid(lexical+vector+graph, RRF+applicability)"
                : "lexical+graph(applicability)";
    }
}
