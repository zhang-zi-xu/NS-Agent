package com.nongxin.service.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.AgentContext;
import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.ToolSubmission;
import com.nongxin.domain.field.FieldProfile;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.RiskService;
import com.nongxin.service.TaskService;
import com.nongxin.service.UploadService;
import com.nongxin.service.impl.KnowledgeLibraryImpl;
import com.nongxin.service.impl.PhenologyServiceImpl;
import com.nongxin.service.knowledge.PrescriptionEvidenceGuard;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Regressions from the 2026-10-03 exported conversation, not expert diagnostic accuracy scores. */
class AnswerReliabilityRegressionTest {
    private final KnowledgeLibrary library =
            new KnowledgeLibraryImpl(
                    new ClassPathResource("kb.json"),
                    new ClassPathResource("knowledge/sources.json"),
                    new ObjectMapper());
    private final ApiKeyService.Resolution selection =
            new ApiKeyService.Resolution(
                    "test-only-unused-key", false, null, "deepseek", "deepseek-flash");

    private Map<String, Object> plan(String task, String field, String text, String source) {
        var item = new LinkedHashMap<String, Object>();
        item.put("task", task);
        item.put(field, text);
        item.put("evidence", List.of(source));
        return new LinkedHashMap<>(
                Map.of("crop", "小麦", "title", "播前安排", "items", new ArrayList<>(List.of(item))));
    }

    @Test
    void sowingMoistureCannotBeRebrandedAsTillageOptimum() {
        String source = "chunk-hubei-maintech-2026-18";
        var incorrect = plan("深翻埋秸秆、旋耕整地", "condition", "土壤含水量接近60%—70%时整地作业最合适", source);
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        incorrect, Set.of(source), library, "湖北"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原文支持");
        String focused = "chunk-hubei-wheat-sowing";
        var correct = plan("播种", "condition", "土壤含水量60%—70%时播种", focused);
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        correct, Set.of(focused), library, "湖北"))
                .doesNotThrowAnyException();
    }

    @Test
    void allPlanTextFieldsAreCheckedAndMethodCannotHideAnUnsupportedDose() {
        for (String field :
                List.of("materials", "method", "condition", "warning", "review", "window")) {
            var unsafe = plan("施肥", field, "叶面喷施20%磷酸二氢钾溶液", "chunk-heat-fertilize");
            assertThatThrownBy(
                            () ->
                                    PrescriptionEvidenceGuard.validate(
                                            unsafe, Set.of("chunk-heat-fertilize"), library))
                    .as(field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void proseDoseWithoutACardIsAlsoRejectedAndNoDraftCardsSurvive() {
        var ctx = new AgentContext("test", Map.of("question", "水稻怎么施肥", "fieldRegion", "浙江"));
        ctx.put(AgriTools.RETRIEVED_CHUNKS, Set.of("chunk-heat-fertilize"));
        var response =
                new ChatAnswerAssembler(library)
                        .assemble(
                                new AgentResult(
                                        "水稻叶面喷施20%磷酸二氢钾溶液，依据chunk-heat-fertilize。",
                                        List.of(), 1, false),
                                ctx,
                                selection,
                                0);
        assertThat(response.degraded()).isTrue();
        assertThat(response.reply()).contains("未通过原文依据核查").doesNotContain("20%");
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void unsafeNumericAdviceCannotBeHiddenInARiskOrClarificationCard() {
        for (String tool : List.of("submit_risk_report", "submit_clarify")) {
            var ctx = new AgentContext("test", Map.of("question", "水稻怎么施肥", "fieldRegion", "浙江"));
            ctx.put(AgriTools.RETRIEVED_CHUNKS, Set.of("chunk-heat-fertilize"));
            var response =
                    new ChatAnswerAssembler(library)
                            .assemble(
                                    new AgentResult(
                                            "先核对现场情况。",
                                            List.of(
                                                    new ToolSubmission(
                                                            tool,
                                                            Map.of(
                                                                    "intro",
                                                                    "叶面喷施20%磷酸二氢钾溶液",
                                                                    "evidence",
                                                                    List.of(
                                                                            "chunk-heat-fertilize")),
                                                            "test-only")),
                                            1,
                                            false),
                                    ctx,
                                    selection,
                                    0);
            // 卡片被丢弃、正文仍然完整可用：按"内容有调整"处理，不再谎报"未完整生成"。
            assertThat(response.degraded()).isFalse();
            assertThat(response.reply()).contains("已省略 1 处缺少同作物原文依据");
            assertThat(response.risk()).isNull();
            assertThat(response.clarify()).isNull();
            assertThat(response.reply()).doesNotContain("20%");
        }
    }

    @Test
    void weatherProbabilityIsNotAPrescriptionButCannotMaskANearbyUnsupportedDose() {
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validateReply(
                                        "整地前关注天气，降水概率60%，日期2026-10-05只是候选。",
                                        null, Set.of(), library))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                PrescriptionEvidenceGuard.validateReply(
                                        "降水概率60%，计划喷施20%磷酸二氢钾溶液。",
                                        "水稻", Set.of("chunk-heat-fertilize"), library))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedSentenceIsRemovedButTheRestOfTheAnswerSurvives() {
        var ctx = new AgentContext("test", Map.of("question", "水稻怎么施肥", "fieldRegion", "浙江"));
        ctx.put(AgriTools.RETRIEVED_CHUNKS, Set.of("chunk-heat-fertilize"));
        var response =
                new ChatAnswerAssembler(library)
                        .assemble(
                                new AgentResult(
                                        "建议先排水晒田，观察根系。叶面喷施20%磷酸二氢钾溶液，依据chunk-heat-fertilize。三天后再看新叶。",
                                        List.of(),
                                        1,
                                        false),
                                ctx,
                                selection,
                                0);
        // 裁剪式降级：只移除无依据的那一句，可操作内容保留（原实现会把整段替换成拒答）。
        assertThat(response.reply()).contains("建议先排水晒田", "三天后再看新叶");
        assertThat(response.reply()).doesNotContain("20%");
        assertThat(response.reply()).contains("已省略 1 处缺少同作物原文依据");
        // 内容完整可用（只省略了 1 处），不再标记为"未完整生成"，用户不需要重试。
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void planKeepsSupportedItemsAndDropsOnlyTheUnsupportedOne() {
        String supported = "chunk-hubei-wheat-sowing";
        String unsupported = "chunk-heat-fertilize";
        var good = new LinkedHashMap<String, Object>();
        good.put("task", "播种");
        good.put("method", "鄂北旱地每亩播种量10—12.5公斤");
        good.put("evidence", List.of(supported));
        var bad = new LinkedHashMap<String, Object>();
        bad.put("task", "叶面喷施");
        bad.put("materials", "20%磷酸二氢钾溶液");
        bad.put("evidence", List.of(unsupported));
        var plan =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "crop",
                                "小麦",
                                "title",
                                "播前安排",
                                "items",
                                new ArrayList<>(List.of(good, bad))));
        var ctx = new AgentContext("test", Map.of("question", "小麦播前怎么安排", "fieldRegion", "湖北"));
        ctx.put(AgriTools.RETRIEVED_CHUNKS, Set.of(supported, unsupported));
        var response =
                new ChatAnswerAssembler(library)
                        .assemble(
                                new AgentResult(
                                        "先把地整好，播期按当地安排。",
                                        List.of(new ToolSubmission("submit_farm_plan", plan, "已登记")),
                                        2,
                                        false),
                                ctx,
                                selection,
                                0);
        assertThat(response.plan()).isNotNull();
        assertThat((List<?>) response.plan().get("items")).hasSize(1);
        assertThat(response.reply()).contains("已省略 1 处缺少同作物原文依据");
        // 方案卡保留了可执行的那一项，正文完整，因此不算"未完整生成"。
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void regionalNumericAdviceNeedsConfirmedFieldRegionNotADeviceCity() {
        String source = "chunk-hubei-wheat-sowing";
        var value = plan("播种", "method", "鄂北旱地每亩播种量10—12.5公斤", source);
        for (String region : List.of("", "河南"))
            assertThatThrownBy(
                            () ->
                                    PrescriptionEvidenceGuard.validate(
                                            value, Set.of(source), library, region))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                PrescriptionEvidenceGuard.validate(
                                        value, Set.of(source), library, "湖北"))
                .doesNotThrowAnyException();
        assertThat(
                        AgriculturalRequestContext.confirmedRegion(
                                List.of(Map.of("role", "user", "content", "参考湖北资料，天气定位是湖北。")),
                                null))
                .isNull();
    }

    @Test
    void preparationSearchRejectsLateSeasonCottonAndAmbiguousCompositeSlices() {
        var hits = library.search("小麦播前整地，秸秆已粉碎还田", "小麦", null, 5);
        assertThat(hits)
                .isNotEmpty()
                .anyMatch(h -> h.chunk().id().equals("chunk-hubei-wheat-soil-prep"));
        assertThat(hits)
                .noneMatch(
                        h ->
                                Set.of(
                                                "chunk-henan-irrigation",
                                                "chunk-henan-foliar",
                                                "chunk-hubei-maintech-2026-27",
                                                "chunk-hubei-maintech-2026-8")
                                        .contains(h.chunk().id()));
        assertThat(
                        library.resolve(List.of("chunk-hubei-maintech-2026-27"))
                                .getFirst()
                                .chunk()
                                .crop())
                .isEqualTo("棉花");
        String source = "chunk-hubei-maintech-2026-18";
        String parent = library.resolve(List.of(source)).getFirst().chunk().text();
        assertThat(
                        library.resolve(List.of("chunk-hubei-wheat-soil-prep"))
                                .getFirst()
                                .chunk()
                                .text())
                .isSubstringOf(parent)
                .doesNotContain("60%");
        assertThat(
                        library.resolve(
                                List.of(
                                        "chunk-hubei-wheat-soil-prep",
                                        "chunk-hubei-wheat-sowing",
                                        "chunk-hubei-wheat-seed-prep")))
                .allSatisfy(hit -> assertThat(hit.chunk().text()).isSubstringOf(parent));
    }

    @Test
    void visibleSourcesIncludeOnlyActuallyCitedAndAcceptedEvidence() {
        var ctx = new AgentContext("test", Map.of());
        ctx.put(
                AgriTools.RETRIEVED_CHUNKS,
                Set.of(
                        "chunk-hubei-wheat-soil-prep",
                        "chunk-henan-irrigation",
                        "chunk-hubei-maintech-2026-27"));
        var response =
                new ChatAnswerAssembler(library)
                        .assemble(
                                new AgentResult(
                                        "播前整地方法见 chunk-hubei-wheat-soil-prep，先核对地区和现场条件。",
                                        List.of(),
                                        1,
                                        false),
                                ctx,
                                selection,
                                0);
        assertThat(response.sources())
                .extracting(c -> c.get("id"))
                .containsExactly("chunk-hubei-wheat-soil-prep");
        var noCitation =
                new ChatAnswerAssembler(library)
                        .assemble(
                                new AgentResult("请先补充田块所在地区。", List.of(), 1, false),
                                ctx,
                                selection,
                                0);
        assertThat(noCitation.sources()).isEmpty();
    }

    @Test
    void dataPreparationQuestionDoesNotBecomeAnExecutionPlanOrClarificationLoop() {
        String question = "我想制定接下来一周的农事计划。需要向你提供哪些田块信息？";
        assertThat(AgriculturalRequestContext.informationChecklist(question)).isTrue();
        var tools =
                new AgriTools(
                                library,
                                mock(PhenologyService.class),
                                mock(RiskService.class),
                                mock(TaskService.class),
                                mock(UploadService.class))
                        .buildRegistry();
        var ctx = new AgentContext("test", Map.of("informationChecklist", true));
        assertThat(tools.execute("submit_farm_plan", Map.of(), ctx)).contains("资料准备清单");
        assertThat(tools.execute("submit_clarify", Map.of(), ctx)).contains("信息清单");
        assertThat(AgriculturalRequestContext.informationChecklist("请按这些信息安排小麦播种计划")).isFalse();
    }

    @Test
    void fieldRegionIsTakenOnlyFromExplicitUserFactsAndNeverAnAssistantClaim() {
        var history =
                List.<Map<String, Object>>of(
                        Map.of("role", "user", "content", "田块在湖北省襄阳市"),
                        Map.of("role", "assistant", "content", "田块在河南"));
        assertThat(AgriculturalRequestContext.confirmedRegion(history, null)).isEqualTo("湖北");
        assertThat(
                        AgriculturalRequestContext.confirmedRegion(
                                List.of(Map.of("role", "user", "content", "田块不在湖北")), null))
                .isNull();
        for (String text : List.of("如果田块在湖北，怎么办？", "田块在湖北吗？", "田块地区不确定"))
            assertThat(
                            AgriculturalRequestContext.confirmedRegion(
                                    List.of(
                                            Map.of("role", "user", "content", "田块在湖北"),
                                            Map.of("role", "user", "content", text)),
                                    null))
                    .as(text)
                    .isNull();
        for (String notes : List.of("如果田块在湖北，怎么办？", "田块在湖北吗？", "田块地区不确定")) {
            var field = new FieldProfile("fixture", "样例", "小麦", null, null, null, notes, List.of());
            assertThat(AgriculturalRequestContext.confirmedRegion(List.of(), field)).isNull();
        }
    }

    @Test
    void oldWheatSowingDateAndFutureSowingDateCannotEstablishCurrentGrowthStage() {
        var service = new PhenologyServiceImpl();
        var old = service.getPhenology("小麦", LocalDate.now().minusDays(155).toString());
        assertThat(old.phaseName()).isNull();
        assertThat(old.note()).contains("历史播期不代表当前仍有作物").doesNotContain("灌浆成熟期");
        assertThat(service.getPhenology("小麦", LocalDate.now().plusDays(10).toString()).note())
                .contains("播期尚未到达");
    }
}
