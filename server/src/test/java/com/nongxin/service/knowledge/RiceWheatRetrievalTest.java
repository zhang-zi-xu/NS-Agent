package com.nongxin.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;
import com.nongxin.service.impl.KnowledgeGraphServiceImpl;
import com.nongxin.service.impl.KnowledgeLibraryImpl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RiceWheatRetrievalTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClassPathResource draft = new ClassPathResource("kb.json");
    private final ClassPathResource sources = new ClassPathResource("knowledge/sources.json");
    private final KnowledgeLibrary base = new KnowledgeLibraryImpl(draft, sources, json);

    private KnowledgeLibrary library(
            KnowledgeGraphService graph, EmbeddingService embedding, VectorIndexService vectors) {
        return new KnowledgeLibraryImpl(
                draft,
                sources,
                60,
                12,
                json,
                provider(embedding),
                provider(vectors),
                provider(graph),
                8);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private record Case(String query, String crop, String region, List<String> expectedSourceIds) {}

    private record Cases(int version, String note, List<Case> cases) {}

    @Test
    void sourceIdBasedRegressionAndSameDepthBaselineAreReproducible() throws Exception {
        var corpus =
                json.readValue(
                        new ClassPathResource("knowledge/rice-wheat-retrieval-cases.json")
                                .getInputStream(),
                        Cases.class);
        var upgraded = library(new KnowledgeGraphServiceImpl(base), null, null);
        int positives = 0, baselineHits = 0, enhancedHits = 0, negatives = 0;
        double baselineMrr = 0, enhancedMrr = 0;
        var details = new ArrayList<Map<String, Object>>();
        for (Case item : corpus.cases()) {
            var actual = upgraded.search(item.query(), item.crop(), item.region(), 3);
            var baseline = upgraded.searchKeywordOnly(item.query(), item.crop(), 3);
            assertThat(actual)
                    .allMatch(
                            h ->
                                    KnowledgeApplicability.supports(
                                            h.chunk(), h.document(), item.crop()));
            if (item.expectedSourceIds().isEmpty()) {
                negatives++;
                assertThat(actual).as("unrelated: %s", item.query()).isEmpty();
            } else {
                positives++;
                assertThat(base.resolve(item.expectedSourceIds()))
                        .hasSize(item.expectedSourceIds().size());
                double expected = reciprocal(actual, item.expectedSourceIds());
                double previous = reciprocal(baseline, item.expectedSourceIds());
                enhancedMrr += expected;
                baselineMrr += previous;
                if (expected > 0) enhancedHits++;
                if (previous > 0) baselineHits++;
                assertThat(expected)
                        .as(
                                "expected source for: %s; actual=%s",
                                item.query(), actual.stream().map(h -> h.chunk().id()).toList())
                        .isPositive();
            }
            details.add(
                    Map.of(
                            "query",
                            item.query(),
                            "baseline",
                            baseline.stream().map(h -> h.chunk().id()).toList(),
                            "enhanced",
                            actual.stream().map(h -> h.chunk().id()).toList(),
                            "expected",
                            item.expectedSourceIds()));
        }
        assertThat(enhancedHits).isGreaterThanOrEqualTo(baselineHits);
        var report = new LinkedHashMap<String, Object>();
        report.put("note", corpus.note());
        report.put("topK", 3);
        report.put("vectorMode", "disabled-no-paid-api");
        report.put("positiveCases", positives);
        report.put("negativeCases", negatives);
        report.put("baselineHits", baselineHits);
        report.put("enhancedHits", enhancedHits);
        report.put("baselineMRR", baselineMrr / positives);
        report.put("enhancedMRR", enhancedMrr / positives);
        report.put("details", details);
        Path output = Path.of("target", "evaluation", "rice-wheat-retrieval.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private double reciprocal(List<KnowledgeLibrary.SourcedHit> hits, List<String> expected) {
        for (int i = 0; i < hits.size(); i++)
            if (expected.contains(hits.get(i).chunk().id())) return 1.0 / (i + 1);
        return 0;
    }

    @Test
    void graphRecallRunsEvenWhenNoEmbeddingKeyIsAvailable() {
        var graph = mock(KnowledgeGraphService.class);
        when(graph.expand(any(), any(), any(), anyInt()))
                .thenReturn(List.of("chunk-pest-rice-blast"));
        var library = library(graph, null, null);
        assertThat(library.search("穗颈瘟预防", "水稻", null, 3)).isNotEmpty();
        verify(graph).expand(any(), any(), any(), anyInt());
    }

    @Test
    void nullEmbeddingResultFallsBackToLexicalAndGraphWithoutChangingCrop() {
        var embedding = mock(EmbeddingService.class);
        var vectors = mock(VectorIndexService.class);
        when(embedding.available()).thenReturn(true);
        when(vectors.ready()).thenReturn(true);
        var library = library(new KnowledgeGraphServiceImpl(base), embedding, vectors);
        assertThat(library.search("小麦条锈病", "小麦", null, 3))
                .isNotEmpty()
                .allMatch(h -> !"水稻".equals(h.chunk().crop()));
        verify(embedding).embed("小麦条锈病");
    }

    @Test
    void relationContextRetainsDifferentSourceWindowsAndApplicabilityWarnings() {
        var library = library(new KnowledgeGraphServiceImpl(base), null, null);
        String context =
                library.formatForModel(
                        base.resolve(List.of("chunk-natesc-rice-pest-2026-9", "chunk-heat-pest")));
        assertThat(context).contains("原文支持的农业关系", "不是本田块诊断", "关系定位原句", "资料口径提示", "不拼接、平均");
        assertThat(
                        library.retrievalGuidance(
                                base.resolve(List.of("chunk-henan-irrigation")),
                                "水稻苗期怎么浇水",
                                "水稻",
                                "浙江"))
                .contains("地区未匹配", "生育期标签未明确覆盖");
        assertThat(library.detectCrop("麦子赤霉病")).isEqualTo("小麦");
        assertThat(library.detectCrop("稻子稻飞虱")).isEqualTo("水稻");
        assertThat(library.detectCrop("水稻和小麦")).isNull();
    }
}
