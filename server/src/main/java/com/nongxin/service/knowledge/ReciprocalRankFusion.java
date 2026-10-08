package com.nongxin.service.knowledge;

import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;
import com.nongxin.service.KnowledgeLibrary.SourcedHit;
import com.nongxin.service.VectorIndexService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable reciprocal-rank fusion of keyword, vector and graph recall. */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {}

    public static List<SourcedHit> fuse(
            List<SourcedHit> keywordHits,
            List<VectorIndexService.Scored> vectorHits,
            List<String> graphHits,
            List<KnowledgeChunk> pool,
            Map<String, KnowledgeDocument> documentsById,
            int rrfK,
            int topK) {
        // ---- RRF 融合：score = Σ 1/(k + rank)，三路互补、无需调权重 ----
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, SourcedHit> byId = new LinkedHashMap<>();
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            SourcedHit hit = keywordHits.get(rank);
            fused.merge(hit.chunk().id(), 1.0 / (rrfK + rank + 1), Double::sum);
            byId.putIfAbsent(hit.chunk().id(), hit);
        }
        Map<String, KnowledgeChunk> chunkById = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : pool) chunkById.put(chunk.id(), chunk);
        for (int rank = 0; rank < vectorHits.size(); rank++) {
            String chunkId = vectorHits.get(rank).chunkId();
            fused.merge(chunkId, 1.0 / (rrfK + rank + 1), Double::sum);
            KnowledgeChunk chunk = chunkById.get(chunkId);
            // 纯向量命中：score 记 1（表示"有依据但非字面命中"，排序由融合分决定）
            if (chunk != null)
                byId.putIfAbsent(
                        chunkId, new SourcedHit(chunk, documentsById.get(chunk.documentId()), 1));
        }

        Map<String, KnowledgeChunk> graphChunkIndex = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : pool) graphChunkIndex.put(chunk.id(), chunk);
        for (int rank = 0; rank < graphHits.size(); rank++) {
            String chunkId = graphHits.get(rank);
            fused.merge(chunkId, 1.0 / (rrfK + rank + 1), Double::sum);
            KnowledgeChunk chunk = graphChunkIndex.get(chunkId);
            if (chunk != null)
                byId.putIfAbsent(
                        chunkId, new SourcedHit(chunk, documentsById.get(chunk.documentId()), 1));
        }

        return fused.entrySet().stream()
                .sorted(
                        (a, b) -> {
                            int cmp = Double.compare(b.getValue(), a.getValue());
                            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
                        })
                .map(entry -> byId.get(entry.getKey()))
                .filter(Objects::nonNull)
                .limit(topK)
                .toList();
    }
}
