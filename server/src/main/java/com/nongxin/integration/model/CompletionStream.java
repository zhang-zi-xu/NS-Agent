package com.nongxin.integration.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.StreamObserver;
import com.nongxin.domain.chat.ProviderException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Parses SSE framing and indexed tool fragments; EOF alone never means success. */
final class CompletionStream {
    private final ObjectMapper json;
    private final StreamObserver observer;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolParts> calls = new TreeMap<>();
    private String finish;
    private int toolArgumentChars;
    private boolean reasoningStatusSent;

    CompletionStream(ObjectMapper json, StreamObserver observer) {
        this.json = json;
        this.observer = observer;
    }

    Map<String, Object> read(InputStream input) throws IOException {
        try (SseFrameReader frames = new SseFrameReader(input, observer)) {
            String payload;
            while ((payload = frames.readData()) != null) {
                observer.check();
                if (payload.equals("[DONE]")) break;
                accept(json.readTree(payload));
                // Usage/heartbeats after an explicit terminal choice are not needed.
                // Do not keep waiting (or accumulating wire overhead) after completion.
                if (finish != null) break;
            }
        }
        observer.check();
        if (finish == null) throw failure("回答连接中断，未收到完整结果，请重试");
        if (!"stop".equals(finish) && !"tool_calls".equals(finish)) {
            throw failure(
                    "length".equals(finish) ? "模型输出达到长度限制，回答未完成，请简化问题后重试" : "供应商未完成回答，请调整问题后重试");
        }
        var tools = new ArrayList<Map<String, Object>>();
        for (ToolParts part : calls.values()) {
            if (part.id.isEmpty() || part.name.isEmpty()) throw failure("供应商返回了不完整的工具调用，请重试");
            tools.add(
                    Map.of(
                            "id",
                            part.id.toString(),
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    part.name.toString(),
                                    "arguments",
                                    part.args.toString())));
        }
        if ("tool_calls".equals(finish) && tools.isEmpty()) throw failure("供应商没有返回有效的工具调用，请重试");
        return new LinkedHashMap<>(Map.of("content", content.toString(), "tool_calls", tools));
    }

    private void accept(JsonNode data) {
        if (data == null || data.has("error")) throw failure("供应商流式响应异常，请稍后重试");
        JsonNode choices = data.path("choices");
        if (!choices.isArray()) throw failure("供应商流式响应格式不正确，请检查接口兼容性");
        for (JsonNode choice : choices) {
            if (choice.path("index").asInt(0) != 0) continue;
            if (choice.path("finish_reason").isTextual()
                    && !choice.get("finish_reason").asText().isBlank())
                finish = choice.get("finish_reason").asText();
            JsonNode delta = choice.path("delta");
            if (delta.path("content").isTextual()) {
                String text = delta.get("content").asText();
                if (content.length() + text.length() > 20_000)
                    throw failure("模型回答过长，未保存为完整答案，请简化问题后重试");
                content.append(text);
                if (!text.isEmpty()) observer.event("delta", Map.of("text", text));
            }
            // Private reasoning is consumed but never buffered into an answer or exposed.
            if (!reasoningStatusSent
                    && delta.path("reasoning_content").isTextual()
                    && !delta.get("reasoning_content").asText().isEmpty()) {
                observer.event("status", Map.of("text", "模型正在分析，请稍候…"));
                reasoningStatusSent = true;
            }
            if (delta.path("tool_calls").isArray())
                for (JsonNode call : delta.get("tool_calls")) {
                    int index = call.path("index").asInt(-1);
                    if (index < 0 || index > 63) throw failure("供应商工具调用格式不正确");
                    ToolParts part = calls.computeIfAbsent(index, unused -> new ToolParts());
                    if (call.path("id").isTextual()) {
                        String id = call.get("id").asText();
                        if (part.id.length() + id.length() > 512) throw failure("供应商工具调用标识过长");
                        part.id.append(id);
                    }
                    JsonNode function = call.path("function");
                    if (function.path("name").isTextual()) {
                        String name = function.get("name").asText();
                        if (part.name.length() + name.length() > 256) throw failure("供应商工具名称过长");
                        part.name.append(name);
                    }
                    if (function.path("arguments").isTextual()) {
                        String arguments = function.get("arguments").asText();
                        if (part.args.length() + arguments.length() > 200_000)
                            throw failure("供应商工具调用参数过长，请简化问题后重试");
                        if (toolArgumentChars + arguments.length() > 400_000)
                            throw failure("供应商工具调用参数总量过长，请简化问题后重试");
                        part.args.append(arguments);
                        toolArgumentChars += arguments.length();
                    }
                }
        }
    }

    private static ProviderException failure(String text) {
        return new ProviderException(text, false);
    }

    private static final class ToolParts {
        final StringBuilder id = new StringBuilder(),
                name = new StringBuilder(),
                args = new StringBuilder();
    }
}
