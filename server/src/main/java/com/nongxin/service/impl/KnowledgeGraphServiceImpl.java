package com.nongxin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeRelation;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.knowledge.AgriculturalQuery;
import com.nongxin.service.knowledge.KnowledgeApplicability;
import com.nongxin.service.knowledge.KnowledgeRelationLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable source-backed graph. Crop-only and all-document fanout are deliberately excluded. */
@Service
public class KnowledgeGraphServiceImpl implements KnowledgeGraphService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphServiceImpl.class);
    private final KnowledgeLibrary library;
    private final Resource resource;
    private final ObjectMapper json;
    private volatile Snapshot snapshot;

    private record Snapshot(
            Map<String, Set<String>> entities,
            Map<String, KnowledgeLibrary.SourcedHit> sources,
            List<KnowledgeRelation> relations,
            int edges) {}

    @Autowired
    public KnowledgeGraphServiceImpl(
            @Lazy KnowledgeLibrary library,
            ObjectMapper json,
            @Value("${nongxin.relations-resource:classpath:knowledge/relations.json}")
                    Resource resource) {
        this.library = library;
        this.json = json;
        this.resource = resource;
    }

    public KnowledgeGraphServiceImpl(KnowledgeLibrary library) {
        this(library, new ObjectMapper(), new ClassPathResource("knowledge/relations.json"));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void build() {
        graph();
    }

    private synchronized Snapshot graph() {
        if (snapshot != null) return snapshot;
        Map<String, Set<String>> entities = new LinkedHashMap<>();
        Map<String, KnowledgeLibrary.SourcedHit> sources = new LinkedHashMap<>();
        for (var hit :
                library.resolve(library.chunks().stream().map(KnowledgeChunk::id).toList())) {
            sources.put(hit.chunk().id(), hit);
            if (hit.document() == null || !hit.document().verified()) continue;
            for (String keyword :
                    hit.chunk().keywords() == null ? List.<String>of() : hit.chunk().keywords()) {
                String entity = AgriculturalQuery.normalize(keyword);
                if (AgriculturalQuery.informativeEntity(entity))
                    entities.computeIfAbsent(entity, ignored -> new LinkedHashSet<>())
                            .add(hit.chunk().id());
            }
        }
        List<KnowledgeRelation> relations = KnowledgeRelationLoader.load(resource, json, library);
        for (KnowledgeRelation relation : relations) {
            for (String value : List.of(relation.subject(), relation.object())) {
                String entity = AgriculturalQuery.normalize(value);
                if (AgriculturalQuery.informativeEntity(entity))
                    entities.computeIfAbsent(entity, ignored -> new LinkedHashSet<>())
                            .add(relation.sourceId());
            }
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        entities.forEach((key, ids) -> frozen.put(key, Set.copyOf(ids)));
        int edges = frozen.values().stream().mapToInt(Set::size).sum();
        snapshot = new Snapshot(Map.copyOf(frozen), Map.copyOf(sources), relations, edges);
        log.info(
                "农业图谱就绪：entities={} sourceEdges={} curatedRelations={}",
                frozen.size(),
                edges,
                relations.size());
        return snapshot;
    }

    @Override
    public Map<String, Object> stats() {
        Snapshot graph = graph();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entityNodes", graph.entities().size());
        stats.put(
                "chunkNodes",
                graph.entities().values().stream().flatMap(Set::stream).distinct().count());
        stats.put("attributionEdges", graph.edges());
        stats.put(
                "documents",
                graph.sources().values().stream()
                        .map(h -> h.chunk().documentId())
                        .distinct()
                        .count());
        stats.put("nodesByType", Map.of("检索实体", graph.entities().size()));
        stats.put("edges", graph.edges());
        stats.put("cooccurrence", 0);
        stats.put("chunks", graph.sources().size());
        stats.put("curatedRelations", graph.relations().size());
        // 关系图的检索扩展深度：问题实体命中主体后，可再沿"主体→客体→主体"走一跳。
        stats.put("maxTraversalHops", 2);
        stats.put(
                "relationsByCrop",
                Map.of(
                        "水稻",
                        graph.relations().stream().filter(r -> "水稻".equals(r.crop())).count(),
                        "小麦",
                        graph.relations().stream().filter(r -> "小麦".equals(r.crop())).count()));
        stats.put("evidenceValidated", true);
        return stats;
    }

    @Override
    public List<String> expand(String query, String crop, Set<String> hitChunkIds, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        Snapshot graph = graph();
        String normalized = AgriculturalQuery.searchText(query);
        Set<String> excluded = hitChunkIds == null ? Set.of() : hitChunkIds;
        Map<String, Integer> scores = new LinkedHashMap<>();
        graph.entities()
                .forEach(
                        (entity, ids) -> {
                            if (!normalized.contains(entity)) return;
                            for (String id : ids) {
                                var hit = graph.sources().get(id);
                                if (excluded.contains(id)
                                        || !KnowledgeApplicability.supports(
                                                hit.chunk(), hit.document(), crop)) continue;
                                scores.merge(id, Math.min(entity.length(), 8), Integer::sum);
                            }
                        });
        // Only traverse a named subject present in the question. Sharing a document is not evidence
        // of relevance.
        Set<String> reachedObjects = new LinkedHashSet<>();
        for (KnowledgeRelation relation : graph.relations()) {
            if (excluded.contains(relation.sourceId())
                    || (crop != null && !crop.equals(relation.crop()))) continue;
            String subject = AgriculturalQuery.normalize(relation.subject());
            if (AgriculturalQuery.informativeEntity(subject) && normalized.contains(subject)) {
                scores.merge(relation.sourceId(), 10, Integer::sum);
                String object = AgriculturalQuery.normalize(relation.object());
                if (AgriculturalQuery.informativeEntity(object)) reachedObjects.add(object);
            }
        }
        // 关系图中的一跳：问题里出现的实体作为主体命中后，沿"主体→客体→（下一跳）主体"再走一步。
        // 只走人工登记过、同一作物、且不在本轮已命中集合里的片段；不做同文档泛化，不跨作物。
        for (KnowledgeRelation relation : graph.relations()) {
            if (excluded.contains(relation.sourceId())
                    || (crop != null && !crop.equals(relation.crop()))) continue;
            String subject = AgriculturalQuery.normalize(relation.subject());
            if (reachedObjects.contains(subject)) scores.merge(relation.sourceId(), 4, Integer::sum);
        }
        return scores.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Integer>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<KnowledgeRelation> relations(Set<String> sourceIds, String crop) {
        if (sourceIds == null || sourceIds.isEmpty()) return List.of();
        return graph().relations().stream()
                .filter(r -> sourceIds.contains(r.sourceId()))
                .filter(r -> crop == null || crop.equals(r.crop()))
                .toList();
    }
}
