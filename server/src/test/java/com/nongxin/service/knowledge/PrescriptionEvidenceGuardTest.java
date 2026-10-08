package com.nongxin.service.knowledge;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.impl.KnowledgeLibraryImpl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;

class PrescriptionEvidenceGuardTest {
    private final KnowledgeLibrary library =
            new KnowledgeLibraryImpl(
                    new ClassPathResource("kb.json"),
                    new ClassPathResource("knowledge/sources.json"),
                    new ObjectMapper());

    private Map<String, Object> plan(String crop, String materials, String source) {
        return Map.of(
                "crop",
                crop,
                "items",
                List.of(
                        Map.of(
                                "task",
                                "叶面喷施",
                                "materials",
                                materials,
                                "evidence",
                                List.of(source))));
    }

    @Test
    void verifiedUnchangedFertilizerQuantityIsAccepted() {
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        plan("水稻", "0.2%磷酸二氢钾溶液", "chunk-heat-fertilize"),
                                        Set.of("chunk-heat-fertilize"),
                                        library))
                .doesNotThrowAnyException();
    }

    @Test
    void fabricatedWrongCropWrongProductAndUnretrievedQuantitiesAreRejected() {
        for (var plan :
                List.of(
                        plan("水稻", "20%磷酸二氢钾溶液", "chunk-heat-fertilize"),
                        plan("水稻", "0.2%尿素溶液", "chunk-heat-fertilize"),
                        plan("小麦", "0.2%磷酸二氢钾溶液", "chunk-heat-fertilize"))) {
            assertThatThrownBy(
                            () ->
                                    PrescriptionEvidenceGuard.validate(
                                            plan, Set.of("chunk-heat-fertilize"), library))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("原文支持");
        }
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        plan("水稻", "0.2%磷酸二氢钾溶液", "chunk-heat-fertilize"),
                                        Set.of(),
                                        library))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userReviewDatesAndNonNumericObservationDoNotNeedPrescriptions() {
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        Map.of(
                                                "crop",
                                                "水稻",
                                                "items",
                                                List.of(
                                                        Map.of(
                                                                "task",
                                                                "观察病斑",
                                                                "review",
                                                                "3天后复查",
                                                                "date",
                                                                "2026-10-03"))),
                                        Set.of(),
                                        library))
                .doesNotThrowAnyException();
    }

    @Test
    void doseForAnotherItemDoesNotAuthorizeThisItem() {
        var plan =
                Map.<String, Object>of(
                        "crop",
                        "水稻",
                        "items",
                        List.of(
                                Map.of("task", "观察", "evidence", List.of("chunk-heat-fertilize")),
                                Map.of("task", "喷施0.2%磷酸二氢钾溶液", "evidence", List.of())));
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        plan, Set.of("chunk-heat-fertilize"), library))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incidencePercentageCannotAuthorizeAnUnnamedSolutionConcentration() {
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        plan("水稻", "20%通用药水", "chunk-pest-rice-sheath"),
                                        Set.of("chunk-pest-rice-sheath"),
                                        library))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equivalentRangePunctuationIsAcceptedButAChangedDepthIsNot() {
        var valid =
                Map.<String, Object>of(
                        "crop",
                        "水稻",
                        "items",
                        List.of(
                                Map.of(
                                        "task",
                                        "灌水保持8-10厘米深水层",
                                        "evidence",
                                        List.of("chunk-heat-water"))));
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        valid, Set.of("chunk-heat-water"), library))
                .doesNotThrowAnyException();
        var invalid =
                Map.<String, Object>of(
                        "crop",
                        "水稻",
                        "items",
                        List.of(
                                Map.of(
                                        "task",
                                        "灌水保持30厘米深水层",
                                        "evidence",
                                        List.of("chunk-heat-water"))));
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        invalid, Set.of("chunk-heat-water"), library))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cropAliasesAreCheckedAndMissingOrAmbiguousCropCannotBypassTheGuard() {
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        plan("稻子", "0.2%磷酸二氢钾溶液", "chunk-heat-fertilize"),
                                        Set.of("chunk-heat-fertilize"),
                                        library))
                .doesNotThrowAnyException();
        for (String crop : List.of("", "通用", "水稻和小麦")) {
            assertThatThrownBy(
                            () ->
                                    PrescriptionEvidenceGuard.validate(
                                            plan(crop, "20%磷酸二氢钾溶液", "chunk-heat-fertilize"),
                                            Set.of("chunk-heat-fertilize"),
                                            library))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void genericLabelCannotAuthorizeCropSpecificNumericPrescriptions() {
        var source = library.resolve(List.of("chunk-heat-fertilize")).getFirst();
        var mixed =
                new KnowledgeChunk(
                        "chunk-mixed",
                        source.chunk().documentId(),
                        "综合资料",
                        "正文",
                        "通用",
                        "全国",
                        "",
                        "水肥",
                        source.chunk().text(),
                        List.of());
        var uncertain = mock(KnowledgeLibrary.class);
        when(uncertain.resolve(List.of("chunk-mixed")))
                .thenReturn(List.of(new KnowledgeLibrary.SourcedHit(mixed, source.document(), 1)));
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        plan("水稻", "0.2%磷酸二氢钾溶液", "chunk-mixed"),
                                        Set.of("chunk-mixed"),
                                        uncertain))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
