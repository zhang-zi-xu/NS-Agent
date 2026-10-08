package com.nongxin.integration.wechat;

import com.nongxin.dto.chat.ChatResponse;

import java.util.List;
import java.util.Map;

/** Renders structured web cards as plain text without adding unverified farm facts. */
public final class WechatReplyFormatter {
    private WechatReplyFormatter() {}

    public static String format(ChatResponse answer) {
        StringBuilder out = new StringBuilder();
        append(out, answer.reply());
        Map<String, Object> clarify = answer.clarify();
        if (clarify != null && clarify.get("items") instanceof List<?> items && !items.isEmpty()) {
            StringBuilder card = new StringBuilder("【还需要确认】");
            append(card, value(clarify.get("intro")));
            int number = 1;
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> item)) continue;
                String question = value(item.get("question"));
                if (question.isBlank()) continue;
                card.append('\n').append(number++).append(". ").append(question);
                if (item.get("options") instanceof List<?> options && !options.isEmpty()) {
                    card.append("（");
                    boolean first = true;
                    for (Object option : options) {
                        String text = value(option);
                        if (text.isBlank()) continue;
                        if (!first) card.append(" / ");
                        card.append(text);
                        first = false;
                    }
                    card.append(" / 暂不确定）");
                }
            }
            card.append("\n可按序回复，也可以直接描述实际情况；不知道的请说暂不确定。");
            append(out, card.toString());
        }
        Map<String, Object> plan = answer.plan();
        if (plan != null && plan.get("items") instanceof List<?> items && !items.isEmpty()) {
            StringBuilder card = new StringBuilder("【农事建议】");
            append(card, value(plan.get("title")));
            append(card, value(plan.get("summary")));
            int number = 1;
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> item)) continue;
                String task = value(item.get("task"));
                if (task.isBlank()) continue;
                card.append('\n').append(number++).append(". ").append(task);
                String condition = value(item.get("condition"));
                if (!condition.isBlank()) card.append("；条件：").append(condition);
                String warning = value(item.get("warning"));
                if (!warning.isBlank()) card.append("；注意：").append(warning);
            }
            card.append("\n以上是建议，不代表已安排或已执行。");
            append(out, card.toString());
        }
        Map<String, Object> risk = answer.risk();
        if (risk != null && !value(risk.get("result")).isBlank()) {
            append(out, "【农情数据筛查】\n" + value(risk.get("result")));
        }
        if (answer.sources() != null && !answer.sources().isEmpty()) {
            StringBuilder sources = new StringBuilder("【本次检索依据】");
            int count = 0;
            for (Map<String, Object> source : answer.sources()) {
                if (source == null || count++ >= 3) break;
                sources.append('\n')
                        .append(value(source.get("id")))
                        .append(" ")
                        .append(value(source.get("institution")))
                        .append("《")
                        .append(value(source.get("title")))
                        .append("》");
                if (!"verified".equals(value(source.get("status")))) sources.append("（原文未核验）");
                String url = value(source.get("url"));
                if (url.startsWith("https://") || url.startsWith("http://"))
                    sources.append(' ').append(url);
            }
            append(out, sources.toString());
        }
        if (answer.degraded()) append(out, "这次回答未完整生成；请核对内容，必要时重新提问。");
        return out.isEmpty() ? "本次没有生成可用回答，请稍后重试。" : out.toString();
    }

    private static String value(Object raw) {
        return raw instanceof String text ? text.trim() : "";
    }

    private static void append(StringBuilder out, String text) {
        if (text == null || text.isBlank()) return;
        if (!out.isEmpty()) out.append("\n\n");
        out.append(text.trim());
    }
}
