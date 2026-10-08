package com.nongxin.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.knowledge.KnowledgeRelation;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.impl.KnowledgeGraphServiceImpl;
import com.nongxin.service.impl.KnowledgeLibraryImpl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;

class KnowledgeRelationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeLibrary library =
            new KnowledgeLibraryImpl(
                    new ClassPathResource("kb.json"),
                    new ClassPathResource("knowledge/sources.json"),
                    json);

    @Test
    void allCuratedRelationsResolveToVerifiedSameCropOriginalQuotes() {
        var relations =
                KnowledgeRelationLoader.load(
                        new ClassPathResource("knowledge/relations.json"), json, library);
        assertThat(relations).hasSize(33);
        assertThat(relations).filteredOn(r -> "水稻".equals(r.crop())).hasSize(18);
        assertThat(relations).filteredOn(r -> "小麦".equals(r.crop())).hasSize(15);
        assertThat(new KnowledgeGraphServiceImpl(library).stats())
                .containsEntry("evidenceValidated", true);
    }

    @Test
    void graphExpandsOneHopAlongCuratedRelationsAndStaysInsideTheCrop() {
        var graph = new KnowledgeGraphServiceImpl(library);
        var relations =
                KnowledgeRelationLoader.load(
                        new ClassPathResource("knowledge/relations.json"), json, library);
        var first =
                relations.stream()
                        .filter(r -> "水稻".equals(r.crop()))
                        .findFirst()
                        .orElseThrow();
        // 用主体作为问题实体：一跳应能带回"客体又作为其他关系主体"的片段。
        var expanded = graph.expand(first.subject(), "水稻", Set.of(), 20);
        assertThat(expanded).isNotEmpty();
        assertThat(expanded).allMatch(id -> id.startsWith("chunk-"));
        // 跨作物不得被带出：小麦关系不能进入水稻扩展结果。
        var wheatSources = relations.stream().filter(r -> "小麦".equals(r.crop())).map(KnowledgeRelation::sourceId).toList();
        var riceOnly = graph.expand(first.subject(), "水稻", Set.of(), 50);
        assertThat(riceOnly).doesNotContainAnyElementsOf(wheatSources);
        assertThat(graph.stats()).containsEntry("maxTraversalHops", 2);
    }

    @Test
    void inventedQuotesCrossCropAndDraftRelationsFailClosed() throws Exception {
        var original =
                new KnowledgeRelation(
                        "test",
                        "水稻",
                        "叶瘟",
                        "防治时机",
                        "田间初见病斑时施药",
                        "chunk-pest-rice-blast",
                        "防治叶瘟在田间初见病斑时施药");
        List<KnowledgeRelation> invalid =
                List.of(
                        new KnowledgeRelation(
                                "test",
                                "小麦",
                                original.subject(),
                                original.predicate(),
                                original.object(),
                                original.sourceId(),
                                original.evidenceQuote()),
                        new KnowledgeRelation(
                                "test",
                                "水稻",
                                original.subject(),
                                original.predicate(),
                                "立即加倍用药",
                                original.sourceId(),
                                "立即加倍用药"),
                        new KnowledgeRelation(
                                "test",
                                "水稻",
                                original.subject(),
                                original.predicate(),
                                original.object(),
                                "chunk-does-not-exist",
                                original.evidenceQuote()));
        for (KnowledgeRelation relation : invalid) {
            var resource =
                    new ByteArrayResource(
                            json.writeValueAsBytes(
                                    new KnowledgeRelationLoader.Bundle(1, List.of(relation))));
            assertThatThrownBy(() -> KnowledgeRelationLoader.load(resource, json, library))
                    .isInstanceOf(IllegalStateException.class);
        }
        var draft =
                library.chunks().stream()
                        .filter(c -> c.id().startsWith("chunk-draft-"))
                        .findFirst()
                        .orElseThrow();
        var invalidDraft =
                new KnowledgeRelation("test", "水稻", "叶瘟", "防治时机", "加药", draft.id(), "加药");
        var resource =
                new ByteArrayResource(
                        json.writeValueAsBytes(
                                new KnowledgeRelationLoader.Bundle(1, List.of(invalidDraft))));
        assertThatThrownBy(() -> KnowledgeRelationLoader.load(resource, json, library))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateIdsAndUnsupportedRelationTypesAreRejected() throws Exception {
        var relation =
                KnowledgeRelationLoader.load(
                                new ClassPathResource("knowledge/relations.json"), json, library)
                        .getFirst();
        var duplicate =
                new ByteArrayResource(
                        json.writeValueAsBytes(
                                new KnowledgeRelationLoader.Bundle(
                                        1, List.of(relation, relation))));
        assertThatThrownBy(() -> KnowledgeRelationLoader.load(duplicate, json, library))
                .isInstanceOf(IllegalStateException.class);
        var inventedType =
                new KnowledgeRelation(
                        relation.id(),
                        relation.crop(),
                        relation.subject(),
                        "确诊概率",
                        relation.object(),
                        relation.sourceId(),
                        relation.evidenceQuote());
        var invalidType =
                new ByteArrayResource(
                        json.writeValueAsBytes(
                                new KnowledgeRelationLoader.Bundle(1, List.of(inventedType))));
        assertThatThrownBy(() -> KnowledgeRelationLoader.load(invalidType, json, library))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void graphHasNoCropOnlyFanoutAndKeepsCropAndRequestSourceBoundaries() {
        var graph = new KnowledgeGraphServiceImpl(library);
        assertThat(graph.expand("水稻", "水稻", Set.of(), 10)).isEmpty();
        assertThat(graph.expand("柑橘黄龙病怎么防治", "小麦", Set.of(), 10)).isEmpty();
        var rice = graph.expand("穗颈瘟怎么预防", "水稻", Set.of(), 10);
        assertThat(rice).contains("chunk-pest-rice-blast", "chunk-natesc-rice-pest-2026-8");
        assertThat(graph.expand("穗颈瘟怎么预防", "小麦", Set.of(), 10)).isEmpty();
        assertThat(graph.expand("穗颈瘟怎么预防", "水稻", Set.copyOf(rice), 10)).isEmpty();
        assertThat(graph.relations(Set.of("chunk-pest-wheat-scab"), "水稻")).isEmpty();
        assertThat(graph.relations(Set.of("chunk-pest-wheat-scab"), "小麦"))
                .allMatch(r -> r.sourceId().equals("chunk-pest-wheat-scab"));
    }

    @Test
    void immutableGraphCanServeConcurrentRequests() {
        var graph = new KnowledgeGraphServiceImpl(library);
        var results =
                java.util.stream.IntStream.range(0, 40)
                        .parallel()
                        .mapToObj(i -> graph.expand("小麦赤霉病", "小麦", Set.of(), 5))
                        .toList();
        assertThat(results).allMatch(results.getFirst()::equals);
    }
}
