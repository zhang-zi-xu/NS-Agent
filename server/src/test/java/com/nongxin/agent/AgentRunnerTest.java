package com.nongxin.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.chat.ProviderException;
import com.nongxin.integration.model.OpenAiCompletionClient;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.impl.RiskServiceImpl;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class AgentRunnerTest {
    private static final String ENDPOINT = "https://provider.invalid/v1/chat/completions";
    private static final String KEY = "never-contact-a-provider-key";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        runner = new AgentRunner(mapper, new OpenAiCompletionClient(mapper, builder.build()));
    }

    @Test
    void defaultClientDoesNotFollowProviderRedirects() throws Exception {
        AtomicInteger redirectedTargetCalls = new AtomicInteger();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://127.0.0.1:" + local.getAddress().getPort() + "/target");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        local.createContext(
                "/target",
                exchange -> {
                    redirectedTargetCalls.incrementAndGet();
                    byte[] body = "unexpected".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        local.start();
        try {
            var response =
                    OpenAiCompletionClient.defaultRestClient()
                            .post()
                            .uri("http://127.0.0.1:" + local.getAddress().getPort() + "/redirect")
                            .body("{}")
                            .retrieve()
                            .toEntity(String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            assertThat(redirectedTargetCalls).hasValue(0);
        } finally {
            local.stop(0);
        }
    }

    @Test
    void returnsComputedRiskAndPreservesProviderToolCallFormat() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                toolResponse("{\"data\":\"降水量60毫米\"}"),
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].type").value("function"))
                .andExpect(
                        jsonPath("$.messages[2].tool_calls[0].function.name")
                                .value("submit_risk_report"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].name").doesNotExist())
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call-1"))
                .andRespond(
                        withSuccess(textResponse("请核实降水统计时段和田间积水情况。"), MediaType.APPLICATION_JSON));

        AgentResult result = runner.run(config(), history());

        assertThat(result.submissions()).hasSize(1);
        assertThat(result.submissions().get(0).args())
                .containsKeys("overall", "summary", "items")
                .doesNotContainKey("data");
        assertThat(result.submissions().get(0).args().get("overall")).isEqualTo("高");
        assertThat(result.rounds()).isEqualTo(2);
        server.verify();
    }

    @Test
    void invalidJsonArgumentsDoNotBecomeSuccessfulSubmissions() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(toolResponse("null"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(textResponse("需要有效数据才能评估。"), MediaType.APPLICATION_JSON));

        assertThat(runner.run(config(), history()).submissions()).isEmpty();
        server.verify();
    }

    @Test
    void failedRiskExecutionDoesNotReturnItsInputAsAReport() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(toolResponse("{\"data\":\"\"}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(textResponse("尚未提供数据。"), MediaType.APPLICATION_JSON));

        assertThat(runner.run(config(), history()).submissions()).isEmpty();
        server.verify();
    }

    @Test
    void preservesComputedRiskWhenProviderFailsToSummarize() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                toolResponse("{\"data\":\"湿度90%\"}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AgentResult result = runner.run(config(), history());

        assertThat(result.degraded()).isTrue();
        assertThat(result.submissions()).hasSize(1);
        assertThat(result.submissions().get(0).args().get("overall")).isEqualTo("中");
        server.verify();
    }

    @Test
    void doesNotExposeProviderErrorBodies() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withStatus(HttpStatus.UNAUTHORIZED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"error\":{\"message\":\"" + KEY + " private-host\"}}"));

        assertThatThrownBy(() -> runner.run(config(), history()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("鉴权失败")
                .hasMessageNotContaining(KEY)
                .hasMessageNotContaining("private-host");
        server.verify();
    }

    @Test
    void reportsTimeoutWithoutExposingTransportDetails() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        request -> {
                            throw new SocketTimeoutException(KEY + " transport details");
                        });

        assertThatThrownBy(() -> runner.run(config(), history()))
                .isInstanceOfSatisfying(
                        ProviderException.class, error -> assertThat(error.timeout()).isTrue())
                .hasMessageContaining("超时")
                .hasMessageNotContaining(KEY);
        server.verify();
    }

    @Test
    void handlesMalformedProviderResponse() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"choices\":\"invalid\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> runner.run(config(), history()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("有效的回答");
        server.verify();
    }

    private static final class Recording implements StreamObserver {
        final List<String> names = new ArrayList<>();
        final List<Object> data = new ArrayList<>();

        public void event(String name, Object data) {
            names.add(name);
            this.data.add(data);
        }
    }

    @Test
    void longProtocolStreamWithShortAnswerCompletesWithoutRetryingTheProvider() throws Exception {
        String chunk =
                "data: "
                        + mapper.writeValueAsString(
                                Map.of(
                                        "id",
                                        "chatcmpl-" + "x".repeat(48),
                                        "object",
                                        "chat.completion.chunk",
                                        "created",
                                        1791032400,
                                        "model",
                                        "test-model",
                                        "system_fingerprint",
                                        "test-fingerprint",
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "delta",
                                                        Map.of(
                                                                "reasoning_content",
                                                                "private-test-placeholder")))))
                        + "\n\n";
        String sse = chunk.repeat(12000) + sseDelta("{\"content\":\"请先核查田块实际情况。\"}", "stop");
        assertThat(sse.length()).isGreaterThan(2_000_000);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));
        Recording recording = new Recording();
        AgentResult result = runner.run(config(), history(), recording);
        assertThat(result.reply()).isEqualTo("请先核查田块实际情况。");
        assertThat(result.degraded()).isFalse();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(recording.data.toString()).doesNotContain(KEY, "private-test-placeholder");
        server.verify();
    }

    @Test
    void streamingToolRoundThenFinalAnswerEmitsStatusDeltasAndSingleReply() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                sseDelta(
                                                "{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"submit_risk_report\",\"arguments\":\"{\\\"data\\\":\\\"湿度90%\\\"}\"}}]}",
                                                "")
                                        + "\n"
                                        + sseDelta(
                                                "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\"}}]}",
                                                "tool_calls"),
                                MediaType.TEXT_EVENT_STREAM));
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                sseDelta("{\"content\":\"风险偏高\"}", "")
                                        + "\n"
                                        + sseDelta("{\"content\":\"，请及时排水。\"}", "stop"),
                                MediaType.TEXT_EVENT_STREAM));

        Recording recording = new Recording();
        AgentResult result = runner.run(config(), history(), recording);

        assertThat(result.reply()).isEqualTo("风险偏高，请及时排水。");
        assertThat(result.submissions()).hasSize(1);
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.degraded()).isFalse();
        assertThat(recording.names).contains("reset", "status", "delta");
        List<String> texts =
                recording.data.stream()
                        .filter(d -> d instanceof Map<?, ?> m && m.get("text") instanceof String)
                        .map(d -> String.valueOf(((Map<?, ?>) d).get("text")))
                        .toList();
        assertThat(texts).contains("风险偏高", "，请及时排水。", "正在核对资料与整理信息…");
        assertThat(texts).noneMatch(t -> t.contains("湿度90%"));
        server.verify();
    }

    @Test
    void gatewayJsonResponseIsAcceptedAsSingleResultWithExplicitStatus() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                mapper.writeValueAsString(
                                        Map.of(
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "finish_reason",
                                                                "stop",
                                                                "message",
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        "兼容网关完整回答"))))),
                                MediaType.APPLICATION_JSON));

        Recording recording = new Recording();
        AgentResult result = runner.run(config(), history(), recording);

        assertThat(result.reply()).isEqualTo("兼容网关完整回答");
        List<String> texts =
                recording.data.stream()
                        .filter(d -> d instanceof Map<?, ?> m && m.get("text") instanceof String)
                        .map(d -> String.valueOf(((Map<?, ?>) d).get("text")))
                        .toList();
        assertThat(texts).contains("供应商返回了完整结果（未采用流式输出）");
        server.verify();
    }

    @Test
    void truncatedStreamReportsUnfinishedInsteadOfFakeSuccess() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                sseDelta("{\"content\":\"半截\"}", ""), MediaType.TEXT_EVENT_STREAM));

        assertThatThrownBy(() -> runner.run(config(), history(), new Recording()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("未收到完整结果");
        server.verify();
    }

    @Test
    void streamingHttpErrorsMapToStableUserMessages() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> runner.run(config(), history(), new Recording()))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("鉴权失败");
        server.verify();
    }

    @Test
    void toolResultsSurviveProviderFailureAsExplicitlyDegraded() throws Exception {
        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                sseDelta(
                                                "{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"submit_risk_report\",\"arguments\":\"{\\\"data\\\":\\\"湿度90%\\\"}\"}}]}",
                                                "")
                                        + "\n"
                                        + sseDelta(
                                                "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\"}}]}",
                                                "tool_calls"),
                                MediaType.TEXT_EVENT_STREAM));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AgentResult result = runner.run(config(), history(), new Recording());

        assertThat(result.degraded()).isTrue();
        assertThat(result.submissions()).hasSize(1);
        assertThat(result.reply()).contains("保留在下方");
        server.verify();
    }

    private String sseDelta(String deltaJson, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":"
                + deltaJson
                + (finish.isEmpty() ? "" : ",\"finish_reason\":\"" + finish + "\"")
                + "}]}\n\n";
    }

    @Test
    void evidenceRejectionReturnsTheRealReasonAndBlocksRepeatSubmissions() throws Exception {
        var calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(
                ToolDefinition.of(
                        "submit_farm_plan",
                        "测试用处方工具",
                        Map.of("type", "object", "properties", Map.of()),
                        (args, ctx) -> {
                            calls.incrementAndGet();
                            throw new IllegalArgumentException(
                                    "具体药肥用量未得到该方案项已核验、同作物原文支持，缺少依据。");
                        }));
        var ctx = new AgentContext("test", Map.of("fieldRegion", "浙江"));
        var config =
                new AgentRunner.Config(
                        "test", ENDPOINT, KEY, "测试系统提示", registry, ctx, 5, null);

        server.expect(requestTo(ENDPOINT))
                .andRespond(
                        withSuccess(
                                toolResponseNamed("submit_farm_plan", "{\"title\":\"方案\"}"),
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andExpect(
                        jsonPath("$.messages[3].content")
                                .value(org.hamcrest.Matchers.containsString("缺少依据")))
                .andExpect(
                        jsonPath("$.messages[3].content")
                                .value(org.hamcrest.Matchers.containsString("不要重复提交")))
                .andRespond(
                        withSuccess(
                                toolResponseNamed("submit_farm_plan", "{\"title\":\"方案\"}"),
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andExpect(
                        jsonPath("$.messages[5].content")
                                .value(org.hamcrest.Matchers.containsString("已因缺少依据")))
                .andRespond(withSuccess(textResponse("换个说法说明缺口。"), MediaType.APPLICATION_JSON));

        AgentResult result = runner.run(config, history());

        // 真实原因回传给模型；第二次同一工具被直接劝返，不再执行，避免空转轮次。
        assertThat(result.reply()).isEqualTo("换个说法说明缺口。");
        assertThat(result.submissions()).isEmpty();
        assertThat(calls).hasValue(1);
        server.verify();
    }

    private String toolResponseNamed(String name, String arguments) throws Exception {
        return mapper.writeValueAsString(
                Map.of(
                        "choices",
                        List.of(
                                Map.of(
                                        "message",
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                "",
                                                "tool_calls",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "call-1",
                                                                "type",
                                                                "function",
                                                                "function",
                                                                Map.of(
                                                                        "name",
                                                                        name,
                                                                        "arguments",
                                                                        arguments))))))));
    }

    private AgentRunner.Config config() {
        ToolRegistry registry =
                new AgriTools(
                                mock(KnowledgeLibrary.class),
                                mock(PhenologyService.class),
                                new RiskServiceImpl(),
                                mock(com.nongxin.service.TaskService.class),
                                mock(com.nongxin.service.UploadService.class))
                        .buildRegistry();
        return new AgentRunner.Config(
                "test",
                ENDPOINT,
                KEY,
                "测试系统提示",
                registry,
                new AgentContext("test", Map.of()),
                5,
                null);
    }

    private List<Map<String, Object>> history() {
        return List.of(Map.of("role", "user", "content", "查看风险"));
    }

    private String toolResponse(String arguments) throws Exception {
        return mapper.writeValueAsString(
                Map.of(
                        "choices",
                        List.of(
                                Map.of(
                                        "message",
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                "",
                                                "tool_calls",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "call-1",
                                                                "type",
                                                                "function",
                                                                "function",
                                                                Map.of(
                                                                        "name",
                                                                        "submit_risk_report",
                                                                        "arguments",
                                                                        arguments))))))));
    }

    private String textResponse(String text) throws Exception {
        return mapper.writeValueAsString(
                Map.of(
                        "choices",
                        List.of(Map.of("message", Map.of("role", "assistant", "content", text)))));
    }
}
