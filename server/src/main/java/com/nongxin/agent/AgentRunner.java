package com.nongxin.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.chat.ProviderException;
import com.nongxin.integration.model.ModelCompletionClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Agent 多轮执行循环（yoked chatWithTools 移植）： 一次请求 → AI 自主选工具 → 执行 → 回传结果 → 再请求，直至无 tool_calls。 submit_*
 * 产出型工具的结果结构化采集。 支持 forceTool：只暴露目标工具 + tool_choice='required'（兼容 DeepSeek/OpenAI）。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_ROUNDS = 5;
    /** 整轮预算默认值：一次问答可能包含"提交方案→被依据核查拦下→改用确认卡再问一轮"两次模型调用。 */
    private static final long DEFAULT_MAX_RUN_SECONDS = 150;
    private static final Set<String> SUBMISSION_TOOLS =
            Set.of("submit_farm_plan", "submit_risk_report", "submit_clarify");
    static final String SUBMISSION_RESULT_KEY = "toolSubmissionResult";

    /** 整轮时间预算（秒），可用 nongxin.agent-max-seconds 调整；前端等待上限为 180 秒。 */
    @org.springframework.beans.factory.annotation.Value("${nongxin.agent-max-seconds:150}")
    private long maxRunSeconds = DEFAULT_MAX_RUN_SECONDS;

    /** 单轮最多几次工具调用轮（可用 nongxin.agent-max-rounds 调整）。 */
    @org.springframework.beans.factory.annotation.Value("${nongxin.agent-max-rounds:5}")
    private int configuredMaxRounds = MAX_ROUNDS;

    private final ObjectMapper objectMapper;
    private final ModelCompletionClient completions;

    public AgentRunner(ObjectMapper objectMapper, ModelCompletionClient completions) {
        this.objectMapper = objectMapper;
        this.completions = completions;
    }

    public record Config(
            String model,
            String endpoint,
            String apiKey,
            String systemPrompt,
            ToolRegistry tools,
            AgentContext ctx,
            int maxRounds,
            String forceTool) {}

    /** 运行 Agent；供应商错误使用不含密钥、原始响应或内部地址的稳定错误信息。 */
    public AgentResult run(Config config, List<Map<String, Object>> history) {
        return run(config, history, null);
    }

    public AgentResult run(
            Config config, List<Map<String, Object>> history, StreamObserver stream) {
        String model = config.model();
        String endpoint = config.endpoint();
        String apiKey = config.apiKey();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", config.systemPrompt()));
        for (Map<String, Object> m : history) {
            Map<String, Object> copy = new LinkedHashMap<>(m);
            messages.add(copy);
        }

        List<ToolSubmission> submissions = new ArrayList<>();
        // 本轮已被"依据核查"拒绝的工具：重复提交同一份方案只会空转轮次与费用，直接劝返。
        Set<String> blockedTools = new java.util.LinkedHashSet<>();
        boolean forced = config.forceTool() != null && !config.forceTool().isBlank();
        int maxRounds =
                config.maxRounds() > 0
                        ? Math.min(config.maxRounds(), Math.max(configuredMaxRounds, 1))
                        : Math.max(configuredMaxRounds, 1);
        int rounds = 0;
        long startedAt = System.nanoTime();
        long runBudgetNanos =
                TimeUnit.SECONDS.toNanos(maxRunSeconds > 0 ? maxRunSeconds : DEFAULT_MAX_RUN_SECONDS);

        while (rounds < maxRounds) {
            long elapsedNanos = System.nanoTime() - startedAt;
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
            long roundStartedAt = System.nanoTime();
            if (stream != null) {
                stream.check();
                stream.event("reset", Map.of());
                stream.event(
                        "status",
                        Map.of(
                                "text",
                                rounds == 0
                                        ? "正在连接模型…"
                                        : "正在结合工具结果整理回答…（已用时 " + elapsedSeconds + " 秒）"));
            }
            if (elapsedNanos >= runBudgetNanos) {
                if (!submissions.isEmpty()) {
                    return new AgentResult(
                            "本次对话处理超时（已用时 "
                                    + elapsedSeconds
                                    + " 秒），已生成的工具结果保留在下方。",
                            submissions,
                            rounds,
                            true);
                }
                throw new ProviderException(
                        "对话处理超时（已用时 " + elapsedSeconds + " 秒），请稍后重试或简化问题", true);
            }
            List<Map<String, Object>> toolDefs = config.tools().collect(config.ctx());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.35);

            if (forced) {
                // 强制工具轮：只暴露目标工具 + required
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> t : toolDefs) {
                    Object fn = t.get("function");
                    if (fn instanceof Map<?, ?> fnMap
                            && config.forceTool().equals(fnMap.get("name"))) {
                        filtered.add(t);
                    }
                }
                body.put("tools", filtered);
                body.put("tool_choice", "required");
                forced = false;
            } else if (!toolDefs.isEmpty()) {
                body.put("tools", toolDefs);
            }

            Map<?, ?> message;
            try {
                message =
                        stream == null
                                ? completions.complete(endpoint, apiKey, body)
                                : completions.stream(endpoint, apiKey, body, stream);
            } catch (ProviderException e) {
                if (!submissions.isEmpty()) {
                    return new AgentResult(
                            e.getMessage() + "。已生成的工具结果保留在下方。", submissions, rounds + 1, true);
                }
                throw e;
            }

            String content = message.get("content") instanceof String s ? s : "";
            List<Map<String, Object>> toolCalls = normalizeToolCalls(message.get("tool_calls"));
            long roundMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStartedAt);
            // 只看大小与耗时，不打印任何正文、密钥或供应商信息：用于回答"为什么这次这么慢"。
            log.info(
                    "Agent 轮次：round={} 模型用时={}ms 上下文={}字 工具调用={}",
                    rounds + 1,
                    roundMs,
                    contextChars(messages),
                    toolCalls.stream().map(tc -> String.valueOf(((Map<?, ?>) tc.get("function")).get("name"))).toList());

            if (toolCalls.isEmpty()) {
                if (content.isBlank()) throw new ProviderException("供应商没有返回可用的回答，请稍后重试", false);
                log.info(
                        "Agent 完成：rounds={} 总用时={}ms",
                        rounds + 1,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                return new AgentResult(content.trim(), submissions, rounds + 1, false);
            }

            // 记录 assistant 消息
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content);
            assistantMsg.put("tool_calls", toolCalls);
            messages.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                if (stream != null) {
                    stream.check();
                    stream.event("status", Map.of("text", "正在核对资料与整理信息…"));
                }
                Map<?, ?> function = (Map<?, ?>) tc.get("function");
                String name = (String) function.get("name");
                String resultText;
                config.ctx().put(SUBMISSION_RESULT_KEY, null);
                if (blockedTools.contains(name)) {
                    resultText =
                            "该工具本轮已因缺少依据或参数问题被拒绝，请不要重复提交；"
                                    + "请改用自然语言向用户说明：缺哪一项依据、可核查的下一步是什么。";
                } else {
                    try {
                        Map<String, Object> args = parseArgs((String) function.get("arguments"));
                        resultText = config.tools().execute(name, args, config.ctx());
                        boolean failed =
                                resultText == null
                                        || resultText.startsWith("工具执行失败")
                                        || resultText.startsWith("错误：");
                        if (failed) {
                            // 记录到日志：便于事后判断"模型是否按预期调用工具"，而不必靠猜
                            log.warn("工具未成功执行：{}｜{}", name, brief(resultText));
                            // 依据核查失败必须把真实原因回传，否则模型以为只是格式问题而反复重试。
                            boolean evidenceRejection =
                                    Boolean.TRUE.equals(
                                                    config.ctx().extra(AgriTools.REJECTED_PRESCRIPTION))
                                            || (resultText != null && resultText.contains("依据"));
                            String reason = reasonOf(resultText);
                            if (evidenceRejection) blockedTools.add(name);
                            resultText =
                                    (reason.isEmpty()
                                                    ? "工具未成功执行，请检查参数并补充必要信息后重试。"
                                                    : "工具未成功执行：" + reason + " ")
                                            + (evidenceRejection
                                                    ? "不要重复提交同一份方案或同一个数值；缺少田块地区等关键信息时，先用 submit_clarify 询问，"
                                                            + "再用自然语言说明缺哪一项依据与可核查的下一步。"
                                                    : "请检查参数后重试。");
                        } else if (SUBMISSION_TOOLS.contains(name)) {
                            Object computedResult = config.ctx().extra(SUBMISSION_RESULT_KEY);
                            // Risk cards must contain the rule engine result, never the model's input
                            // text.
                            if (computedResult != null) {
                                Map<String, Object> output =
                                        objectMapper.convertValue(
                                                computedResult, new TypeReference<>() {});
                                submissions.add(new ToolSubmission(name, output, resultText));
                            } else if (!"submit_risk_report".equals(name)) {
                                submissions.add(new ToolSubmission(name, args, resultText));
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("工具参数无效：{}｜{}", name, brief(e.getMessage()));
                        resultText = "工具参数格式不正确，请按工具定义提供 JSON 对象。";
                    }
                }
                messages.add(
                        Map.of(
                                "role",
                                "tool",
                                "tool_call_id",
                                tc.get("id"),
                                "content",
                                resultText.length() > 8000
                                        ? resultText.substring(0, 8000)
                                        : resultText));
            }
            rounds++;
        }

        String fallback =
                submissions.isEmpty() ? "工具调用轮次已用尽，请简化问题后重试。" : "工具调用轮次已用尽，已为你整理出上方结构化结果，可继续追问。";
        return new AgentResult(fallback, submissions, rounds, true);
    }

    private List<Map<String, Object>> normalizeToolCalls(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> call)) continue;
            Object id = call.get("id");
            Object fn = call.get("function");
            if (!(id instanceof String callId)
                    || callId.isBlank()
                    || !(fn instanceof Map<?, ?> fnMap)) continue;
            Object name = fnMap.get("name");
            Object args = fnMap.get("arguments");
            if (!(name instanceof String toolName) || toolName.isBlank()) continue;
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("id", id);
            tc.put("type", "function");
            tc.put(
                    "function",
                    Map.of(
                            "name",
                            name,
                            "arguments",
                            args instanceof String s
                                    ? s
                                    : objectMapper
                                            .valueToTree(args == null ? Map.of() : args)
                                            .toString()));
            out.add(tc);
        }
        return out;
    }

    /** 上下文规模（只统计消息文本字符数，不序列化图片与请求体）。 */
    private static int contextChars(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            Object content = message.get("content");
            if (content instanceof String text) total += text.length();
            else if (content instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> map && map.get("text") instanceof String text)
                        total += text.length();
                }
            }
        }
        return total;
    }

    /** 日志用的短文本：只保留长度，不把整段工具输出写进日志。 */
    private static String brief(String text) {
        if (text == null) return "null";
        return "[chars=" + text.length() + "]";
    }

    /** 回传给模型的失败原因：去掉内部前缀、限长，避免把整段内部文本塞进上下文。 */
    static String reasonOf(String text) {
        if (text == null) return "";
        String reason = text.replaceFirst("^(工具执行失败：|错误：)", "").trim();
        return reason.length() > 300 ? reason.substring(0, 300) + "…" : reason;
    }

    private Map<String, Object> parseArgs(String json) {
        try {
            var node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("参数必须为对象");
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数格式不正确");
        }
    }
}
