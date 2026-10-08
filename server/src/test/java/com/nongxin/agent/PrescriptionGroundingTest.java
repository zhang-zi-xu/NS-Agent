package com.nongxin.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.RiskService;
import com.nongxin.service.TaskService;
import com.nongxin.service.UploadService;
import com.nongxin.service.chat.ChatAnswerAssembler;
import com.nongxin.service.impl.KnowledgeLibraryImpl;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class PrescriptionGroundingTest {
    private final KnowledgeLibrary library =
            new KnowledgeLibraryImpl(
                    new ClassPathResource("kb.json"),
                    new ClassPathResource("knowledge/sources.json"),
                    new ObjectMapper());
    private final ToolRegistry tools =
            new AgriTools(
                            library,
                            mock(PhenologyService.class),
                            mock(RiskService.class),
                            mock(TaskService.class),
                            mock(UploadService.class))
                    .buildRegistry();

    private Map<String, Object> plan(String amount) {
        var item = new LinkedHashMap<String, Object>();
        item.put("task", "叶面喷施");
        item.put("materials", amount + "磷酸二氢钾溶液");
        item.put("evidence", List.of("chunk-heat-fertilize"));
        return new LinkedHashMap<>(
                Map.of("title", "资料条件下的方案", "crop", "水稻", "items", new ArrayList<>(List.of(item))));
    }

    @Test
    void rejectedToolCannotReappearAsAValidPlanOrNumericReplyAndValidRetryClearsIt() {
        var ctx = new AgentContext("test", Map.of("fieldRegion", "浙江"));
        ctx.put(AgriTools.RETRIEVED_CHUNKS, Set.of("chunk-heat-fertilize"));
        var unsafe = plan("20%");
        assertThat(tools.execute("submit_farm_plan", unsafe, ctx)).startsWith("工具执行失败");
        assertThat(ctx.extra(AgriTools.REJECTED_PRESCRIPTION)).isEqualTo(true);
        var assembler = new ChatAnswerAssembler(library);
        var config =
                new ApiKeyService.Resolution(
                        "test-key-not-used", false, null, "deepseek", "deepseek-flash");
        var response =
                assembler.assemble(
                        new AgentResult(
                                "请喷20%溶液",
                                List.of(new ToolSubmission("submit_farm_plan", unsafe, "假成功")),
                                1,
                                false),
                        ctx,
                        config,
                        0);
        assertThat(response.plan()).isNull();
        assertThat(response.reply()).contains("未通过原文依据核查").doesNotContain("20%");
        assertThat(response.degraded()).isTrue();

        var safe = plan("0.2%");
        assertThat(tools.execute("submit_farm_plan", safe, ctx)).startsWith("已登记处方单");
        assertThat(ctx.extra(AgriTools.REJECTED_PRESCRIPTION)).isEqualTo(false);
        var recovered =
                assembler.assemble(
                        new AgentResult(
                                "请先确认适用条件",
                                List.of(new ToolSubmission("submit_farm_plan", safe, "已登记")),
                                2,
                                false),
                        ctx,
                        config,
                        0);
        assertThat(recovered.plan()).isNotNull();
        assertThat(recovered.degraded()).isFalse();
    }

    @Test
    void assemblyLogsOnlyCountsNeverBodyOrKey() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatAnswerAssembler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var ctx = new AgentContext("test", Map.of());
            ctx.put(AgriTools.REJECTED_PRESCRIPTION, true);
            var config =
                    new ApiKeyService.Resolution(
                            "secret-key-marker", false, null, "deepseek", "deepseek-flash");
            new ChatAnswerAssembler(library)
                    .assemble(
                            new AgentResult("private-farm-marker", List.of(), 1, false),
                            ctx,
                            config,
                            0);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                    .allSatisfy(
                            message ->
                                    assertThat(message)
                                            .doesNotContain(
                                                    "secret-key-marker", "private-farm-marker"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
