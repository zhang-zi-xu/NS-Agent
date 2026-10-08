package com.nongxin.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.StreamObserver;
import com.nongxin.domain.chat.ProviderException;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class CompletionStreamTest {
    private final ObjectMapper json = new ObjectMapper();

    private static class Recording implements StreamObserver {
        final List<String> names = new ArrayList<>();
        final List<Object> data = new ArrayList<>();

        public void event(String name, Object data) {
            names.add(name);
            this.data.add(data);
        }
    }

    private Recording read(String sse) throws Exception {
        Recording recording = new Recording();
        new CompletionStream(json, recording)
                .read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        return recording;
    }

    private Map<String, Object> readMap(String sse) throws Exception {
        Recording recording = new Recording();
        return new CompletionStream(json, recording)
                .read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
    }

    private static String delta(String text, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + text
                + "\"}"
                + (finish.isEmpty() ? "" : ",\"finish_reason\":\"" + finish + "\"")
                + "}]}\n\n";
    }

    @Test
    void manyReasoningChunksAndProtocolFieldsDoNotRejectAShortCompletedAnswer() throws Exception {
        String chunk =
                "data: "
                        + json.writeValueAsString(
                                Map.of(
                                        "id",
                                        "chatcmpl-" + "a".repeat(48),
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
        String sse = chunk.repeat(12000) + delta("先核查田块实际情况。", "stop") + "data: [DONE]\n\n";
        assertThat(sse.length()).isGreaterThan(2_000_000);
        Recording recording = new Recording();
        Map<String, Object> message =
                new CompletionStream(json, recording)
                        .read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        assertThat(message.get("content")).isEqualTo("先核查田块实际情况。");
        assertThat(recording.data.toString()).doesNotContain("private-test-placeholder");
        assertThat(recording.names).containsExactly("status", "delta");
    }

    @Test
    void smallTextDeltasWithLargeProtocolOverheadStillProduceACompleteAnswer() throws Exception {
        String chunk =
                "data: "
                        + json.writeValueAsString(
                                Map.of(
                                        "id",
                                        "chatcmpl-" + "b".repeat(48),
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
                                                        Map.of("content", "字")))))
                        + "\n\n";
        String sse = chunk.repeat(12000) + delta("完成", "stop");
        assertThat(sse.length()).isGreaterThan(2_000_000);
        assertThat(readMap(sse).get("content")).isEqualTo("字".repeat(12000) + "完成");
    }

    @Test
    void reasoningOnlyWithoutTerminalFinishIsStillIncomplete() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "data:"
                                            + " {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"test-private\"}}]}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("未收到完整结果")
                .hasMessageNotContaining("test-private");
    }

    @Test
    void completedChoiceDoesNotWaitForMoreProviderData() throws Exception {
        byte[] sse = delta("完整结果", "stop").getBytes(StandardCharsets.UTF_8);
        var input =
                new ByteArrayInputStream(sse) {
                    @Override
                    public synchronized int read(byte[] buffer, int offset, int length) {
                        if (available() == 0)
                            throw new AssertionError("must not read after terminal event");
                        return super.read(buffer, offset, length);
                    }
                };
        assertThat(new CompletionStream(json, new Recording()).read(input).get("content"))
                .isEqualTo("完整结果");
    }

    @Test
    void blankFinishReasonDoesNotEndTheAnswerEarly() throws Exception {
        assertThat(
                        readMap(
                                        "data:"
                                            + " {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"先\"},\"finish_reason\":\"\"}]}\n\n"
                                                + delta("完成", "stop"))
                                .get("content"))
                .isEqualTo("先完成");
    }

    @Test
    void aggregateToolArgumentsStayBoundedAcrossSeparateCalls() throws Exception {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            calls.add(
                    Map.of(
                            "index",
                            index,
                            "id",
                            "call-" + index,
                            "function",
                            Map.of("name", "test", "arguments", "x".repeat(150_000))));
        }
        String sse =
                "data: "
                        + json.writeValueAsString(
                                Map.of(
                                        "choices",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "delta",
                                                        Map.of("tool_calls", calls),
                                                        "finish_reason",
                                                        "tool_calls"))))
                        + "\n\n";
        assertThatThrownBy(() -> read(sse))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("参数总量过长");
    }

    @Test
    void toolIdentifiersAndNamesCannotConsumeTheExpandedWireBudget() throws Exception {
        for (var function :
                List.of(
                        Map.of("id", "x".repeat(513), "function", Map.of("name", "test")),
                        Map.of("id", "call-1", "function", Map.of("name", "x".repeat(257))))) {
            Map<String, Object> call = new java.util.LinkedHashMap<>(function);
            call.put("index", 0);
            String sse =
                    "data: "
                            + json.writeValueAsString(
                                    Map.of(
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "delta",
                                                            Map.of("tool_calls", List.of(call))))))
                            + "\n\n";
            assertThatThrownBy(() -> read(sse))
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("过长");
        }
    }

    @Test
    void assemblesTextDeltasAndEmitsThemInOrder() throws Exception {
        Recording recording = new Recording();
        Map<String, Object> message =
                new CompletionStream(json, recording)
                        .read(
                                new ByteArrayInputStream(
                                        ("data:"
                                             + " {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"先观察\"}}]}\n\n"
                                             + "data:"
                                             + " {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"叶片\"},\"finish_reason\":\"stop\"}]}\n\n")
                                                .getBytes(StandardCharsets.UTF_8)));

        assertThat(message.get("content")).isEqualTo("先观察叶片");
        assertThat(message.get("tool_calls")).isEqualTo(List.of());
        assertThat(recording.names).containsExactly("delta", "delta");
        List<String> texts =
                recording.data.stream()
                        .map(d -> String.valueOf(((Map<?, ?>) d).get("text")))
                        .toList();
        assertThat(texts).containsExactly("先观察", "叶片");
    }

    @Test
    void ignoresKeepAliveCommentsCrlfAndReasoningContent() throws Exception {
        Map<String, Object> message =
                readMap(
                        ": keepalive\r\n\r\n"
                            + "data:"
                            + " {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"私密推理\",\"content\":\"公开\"},\"finish_reason\":\"stop\"}]}\r\n\r\n");
        assertThat(message.get("content")).isEqualTo("公开");
    }

    @Test
    void joinsMultipleDataLinesIntoOnePayload() throws Exception {
        Map<String, Object> message =
                readMap(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"断\"},\n"
                                + "data: \"finish_reason\":\"stop\"}]}\n\n");
        assertThat(message.get("content")).isEqualTo("断");
    }

    @Test
    void assemblesFragmentedToolCallsAcrossDeltas() throws Exception {
        Map<String, Object> message =
                readMap(
                        "data:"
                            + " {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-\",\"function\":{\"name\":\"submit_clar\",\"arguments\":\"{\\\"items\\\":[{\"}}]}}]}}\n\n"
                            + "data:"
                            + " {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"1\",\"function\":{\"name\":\"ify\",\"arguments\":\"\\\"question\\\":\\\"作物？\\\"}]}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n");
        List<?> calls = (List<?>) message.get("tool_calls");
        assertThat(calls).hasSize(1);
        Map<?, ?> call = (Map<?, ?>) calls.getFirst();
        assertThat(call.get("id")).isEqualTo("call-1");
        Map<?, ?> fn = (Map<?, ?>) call.get("function");
        assertThat(fn.get("name")).isEqualTo("submit_clarify");
        assertThat((String) fn.get("arguments")).contains("作物？").contains("\"items\"");
    }

    @Test
    void eofWithoutFinishNeverCountsAsSuccess() {
        assertThatThrownBy(() -> read(delta("半截", "")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("未收到完整结果");
    }

    @Test
    void doneMarkerWithoutFinishStillFails() {
        assertThatThrownBy(() -> read("data: [DONE]\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("未收到完整结果");
    }

    @Test
    void lengthFinishIsReportedAsUnfinished() {
        assertThatThrownBy(() -> read(delta("截断", "length")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("长度限制");
    }

    @Test
    void otherFinishReasonsAreReportedAsUnfinished() {
        assertThatThrownBy(() -> read(delta("内容", "content_filter")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("未完成回答");
    }

    @Test
    void errorPayloadFailsWithoutExposingBody() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "data:"
                                            + " {\"error\":{\"message\":\"private-upstream-secret\"}}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("流式响应异常")
                .hasMessageNotContaining("private-upstream-secret");
    }

    @Test
    void malformedChoicesShapeFails() {
        assertThatThrownBy(() -> read("data: {\"choices\":\"invalid\"}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("格式不正确");
    }

    @Test
    void toolCallFinishWithoutAnyCallFails() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "data:"
                                            + " {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("有效的工具调用");
    }

    @Test
    void nonZeroChoiceIndexIsIgnored() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "data:"
                                            + " {\"choices\":[{\"index\":1,\"delta\":{\"content\":\"忽略\"},\"finish_reason\":\"stop\"}]}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("未收到完整结果");
    }

    @Test
    void contentBeyondTwentyThousandCharactersFails() {
        assertThatThrownBy(() -> read(delta("字".repeat(20001), "stop")))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("回答过长");
    }

    @Test
    void hugeSingleDataLineFailsBeforeParsingOrAccumulatingAnAnswer() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                                                + "x".repeat(2_000_000)
                                                + "\"}}]}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("单条流式数据过大");
    }

    @Test
    void oversizedToolArgumentsFailInsteadOfBeingBuffered() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "data:"
                                            + " {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"submit_risk_report\",\"arguments\":\""
                                            + "{\\\"data\\\":\\\""
                                                + "x".repeat(200_001)
                                                + "\\\"}\"}}]}}]}\n\n"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("工具调用参数过长");
    }

    @Test
    void observerCancellationStopsReading() {
        Recording recording =
                new Recording() {
                    public boolean cancelled() {
                        return true;
                    }
                };
        assertThatThrownBy(
                        () ->
                                new CompletionStream(json, recording)
                                        .read(
                                                new ByteArrayInputStream(
                                                        "data: {}\n\n"
                                                                .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
