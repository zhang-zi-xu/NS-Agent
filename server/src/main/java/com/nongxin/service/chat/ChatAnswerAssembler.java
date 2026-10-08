package com.nongxin.service.chat;

import com.nongxin.agent.AgentContext;
import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.ToolSubmission;
import com.nongxin.domain.field.FieldProfile;
import com.nongxin.dto.chat.ChatResponse;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.knowledge.AgriculturalQuery;
import com.nongxin.service.knowledge.PrescriptionEvidenceGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Enforces server-observed citations and assembles structured replies for every channel. */
@Component
public class ChatAnswerAssembler {
    private static final Logger log = LoggerFactory.getLogger(ChatAnswerAssembler.class);
    private final KnowledgeLibrary library;

    public ChatAnswerAssembler(KnowledgeLibrary library) {
        this.library = library;
    }

    private static final Pattern ORAL_CLARIFY_RE =
            Pattern.compile("请.?确认|点一下|点选|直接回我|回答我|告诉我|选一下|回复我");

    private static final Pattern SOURCE_ID_RE = Pattern.compile("chunk-[A-Za-z0-9-]{1,80}");

    private static final Pattern CARD_RECEIPT_PART =
            Pattern.compile(
                    "(?:(?:确认卡|确认清单|方案卡|方案|处方单|卡片)(?:已经|已)?(?:生成|整理|提交|登记|发出|发送|准备)(?:完毕|完成|好)?了?"
                            + "|请?(?:查看|点选|填写|提交|完成)(?:上方|下方)?的?(?:确认卡|确认清单|方案卡|方案|处方单|卡片)"
                            + "|(?:共|一共)\\d+项(?:动作|任务)?|点一下就行)");

    public ChatResponse assemble(
            AgentResult result,
            AgentContext ctx,
            ApiKeyService.Resolution resolved,
            int imageCount) {
        Set<String> allowed = allowedSources(ctx);
        Map<String, Object> plan =
                sanitizePlanEvidence(submissionArgs(result, "submit_farm_plan"), allowed);
        Map<String, Object> risk =
                sanitizePlanEvidence(submissionArgs(result, "submit_risk_report"), allowed);
        Map<String, Object> clarify = sanitizePlanEvidence(submissionArgs(result, "submit_clarify"), allowed);
        String reply = sanitizeCitations(result.reply(), allowed);
        boolean toolRejected = Boolean.TRUE.equals(ctx.extra(AgriTools.REJECTED_PRESCRIPTION));
        String crop = resolveCrop(plan, ctx, reply);
        List<String> notices = new ArrayList<>();

        // 逐项核查方案卡：只裁掉不合规的项，保留其余可执行内容（原实现会清空整张卡）。
        if (plan != null) plan = prunePlan(plan, crop, ctx, notices);
        // 逐句核查正文：只移除含无依据数值的句子，结论、观察步骤与时间窗口保留。
        reply = pruneReply(reply, crop, ctx, notices, toolRejected);
        // 风险卡与确认卡各自独立核查，失败只丢该卡，不牵连其他内容。
        risk = dropInvalidCard(risk, crop, ctx, notices);
        clarify = dropInvalidCard(clarify, crop, ctx, notices);

        if (notices.isEmpty() && toolRejected) notices.add(removedNotice(1));
        // "未完整生成"只用于：供应商失败/超时/轮次用尽，或正文被裁到没有可用内容（只能用兜底说明）。
        // 只是裁掉少数无依据表述、其余内容完整可用的回答，不再标记为未完成——否则用户会以为要重试。
        boolean fallbackOnly = reply == null || reply.isBlank();
        if (fallbackOnly) {
            reply = fallbackReply(ctx.extraString("fieldRegion"));
        } else if (!notices.isEmpty()) {
            reply = reply.strip() + "\n\n" + String.join("", notices);
        }
        reply = capLength(reply);
        boolean degraded = result.degraded() || fallbackOnly;

        // A retrieved candidate is not an answer citation. Only explicit references in the
        // accepted text/cards belong in the visible evidence list.
        Set<String> cited = new LinkedHashSet<>();
        collectCitations(reply, cited);
        collectEvidence(plan, cited);
        collectEvidence(risk, cited);
        collectEvidence(clarify, cited);
        cited.retainAll(allowed);
        List<Map<String, Object>> sources = cited.isEmpty() ? List.of() : library.cards(cited);
        int sections = sectionCount(reply);
        log.info(
                "chat 完成：rounds={} submissions={} degraded={} sources={} images={} replyChars={} pruned={} sections={}",
                result.rounds(),
                result.submissions().stream().map(ToolSubmission::name).toList(),
                degraded,
                sources.size(),
                imageCount,
                reply == null ? 0 : reply.length(),
                notices.size(),
                sections);
        if (reply != null && reply.length() > 400) {
            // 正文长度预算目前写在提示词里，这里只做可观测统计与硬上限兜底。
            log.info("回答超过 400 字预算：replyChars={}", reply.length());
        }

        return new ChatResponse(
                reply,
                plan,
                risk,
                clarify,
                sources,
                result.rounds(),
                resolved.provider(),
                resolved.model(),
                degraded);
    }

    /** 正文硬上限：只做兜底截断，不改变正常长度的回答。 */
    private static final int REPLY_CHAR_LIMIT = 1500;

    private String resolveCrop(Map<String, Object> plan, AgentContext ctx, String reply) {
        String crop = null;
        if (plan != null && plan.get("crop") instanceof String label)
            crop = AgriculturalQuery.crop(label);
        if (crop == null) crop = AgriculturalQuery.crop(ctx.extraString("question"));
        if (crop == null && ctx.extra("field") instanceof FieldProfile field)
            crop = AgriculturalQuery.crop(field.crop());
        if (crop == null) crop = AgriculturalQuery.crop(reply);
        return crop;
    }

    /** 方案卡逐项核查：保留通过核查的项；全部不通过时整卡移除，但正文与卡片不受牵连。 */
    private Map<String, Object> prunePlan(
            Map<String, Object> plan, String crop, AgentContext ctx, List<String> notices) {
        Set<String> allowed = allowedSources(ctx);
        String region = ctx.extraString("fieldRegion");
        if (!(plan.get("items") instanceof List<?> items) || items.isEmpty()) {
            try {
                PrescriptionEvidenceGuard.validate(plan, allowed, library, region);
                return plan;
            } catch (IllegalArgumentException rejected) {
                notices.add(removedNotice(1));
                return null;
            }
        }
        List<Object> kept = new ArrayList<>();
        int removed = 0;
        for (Object item : items) {
            Map<String, Object> single = new LinkedHashMap<>(plan);
            // 单项核查只看这一项；标题与摘要最后统一核查，避免一处措辞牵连同卡其他项。
            single.remove("title");
            single.remove("summary");
            single.put("items", List.of(item));
            try {
                PrescriptionEvidenceGuard.validate(single, allowed, library, region);
                kept.add(item);
            } catch (IllegalArgumentException rejected) {
                removed++;
            }
        }
        if (kept.isEmpty()) {
            notices.add(removedNotice(Math.max(removed, 1)));
            return null;
        }
        Map<String, Object> pruned = new LinkedHashMap<>(plan);
        pruned.put("items", kept);
        try {
            PrescriptionEvidenceGuard.validate(pruned, allowed, library, region);
        } catch (IllegalArgumentException rejected) {
            notices.add(removedNotice(removed + 1));
            return null;
        }
        if (removed > 0) notices.add(removedNotice(removed));
        return pruned;
    }

    /** 正文逐句核查：保留可以通过核查的句子，只移除含无依据数值的句子。 */
    private String pruneReply(
            String reply, String crop, AgentContext ctx, List<String> notices, boolean strict) {
        if (reply == null || reply.isBlank()) return reply;
        Set<String> supporting = new LinkedHashSet<>();
        collectCitations(reply, supporting);
        supporting.retainAll(allowedSources(ctx));
        String region = ctx.extraString("fieldRegion");
        StringBuilder kept = new StringBuilder();
        int removed = 0;
        for (String sentence : reply.split("(?<=[。！？!?\\n])")) {
            if (sentence.isBlank()) continue;
            try {
                if (strict) {
                    // 本轮已有处方被拒：正文里的任何数量都必须有同作物已核验原文支持。
                    PrescriptionEvidenceGuard.validateReplyStrict(
                            sentence, crop, supporting, library, region);
                }
                PrescriptionEvidenceGuard.validateReply(sentence, crop, supporting, library, region);
                kept.append(sentence);
            } catch (IllegalArgumentException rejected) {
                removed++;
            }
        }
        if (removed == 0) return reply;
        notices.add(removedNotice(removed));
        return kept.toString().strip();
    }

    private Map<String, Object> dropInvalidCard(
            Map<String, Object> card, String crop, AgentContext ctx, List<String> notices) {
        if (card == null) return null;
        try {
            validateCard(card, crop, ctx);
            return card;
        } catch (IllegalArgumentException rejected) {
            notices.add(removedNotice(1));
            return null;
        }
    }

    private static String removedNotice(int count) {
        return "（已省略 " + count + " 处缺少同作物原文依据的用量或作业条件表述；补齐田块所在县市或核对当地登记标签后可再核。）";
    }

    private static String fallbackReply(String fieldRegion) {
        return "这次涉及具体用量或作业条件的部分未通过原文依据核查，我先不提供数值方案。"
                + (fieldRegion == null || fieldRegion.isBlank()
                        ? "田块所在地区还未确认，地方资料中的数值不能直接套用；请先补充田块所在的县市。"
                        : "还需核对原文中的操作目的与适用条件，不能把其他作业或材料的数值直接套用。")
                + "涉及用药时，还需核对适用于该作物和用途的有效登记标签。";
    }

    private static String capLength(String reply) {
        if (reply == null || reply.length() <= REPLY_CHAR_LIMIT) return reply;
        String cut = reply.substring(0, REPLY_CHAR_LIMIT);
        int boundary =
                Math.max(cut.lastIndexOf('。'), Math.max(cut.lastIndexOf('\n'), cut.lastIndexOf('；')));
        if (boundary > REPLY_CHAR_LIMIT / 2) cut = cut.substring(0, boundary + 1);
        return cut + "\n\n（回答较长已截断，可继续追问剩余部分。）";
    }

    /** 统计回答里出现几段结构标记：①你提供的事实 ②资料支持 ③仍需核实。 */
    static int sectionCount(String reply) {
        if (reply == null || reply.isBlank()) return 0;
        int count = 0;
        if (reply.contains("你提供的事实")) count++;
        if (reply.contains("资料支持")) count++;
        if (reply.contains("仍需核实")) count++;
        return count;
    }

    private Map<String, Object> submissionArgs(AgentResult result, String name) {
        return result.submissions().stream()
                .filter(s -> name.equals(s.name()))
                .map(ToolSubmission::args)
                .findFirst()
                .orElse(null);
    }

    private Set<String> retrieved(AgentContext ctx) {
        return ctx.extra(AgriTools.RETRIEVED_CHUNKS) instanceof Set<?> ids && !ids.isEmpty()
                ? ids.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
    }

    private Set<String> allowedSources(AgentContext ctx) {
        return retrieved(ctx);
    }

    private static void collectCitations(String text, Set<String> cited) {
        if (text == null) return;
        var matcher = SOURCE_ID_RE.matcher(text);
        while (matcher.find()) cited.add(matcher.group());
    }

    private static void collectEvidence(Object value, Set<String> cited) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if ("evidence".equals(entry.getKey()) && entry.getValue() instanceof List<?> ids) {
                    for (Object id : ids) if (id instanceof String text) cited.add(text);
                } else collectEvidence(entry.getValue(), cited);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) collectEvidence(item, cited);
        } else if (value instanceof String text) collectCitations(text, cited);
    }

    private void validateCard(Map<String, Object> card, String crop, AgentContext ctx) {
        if (card == null) return;
        Set<String> supporting = new LinkedHashSet<>();
        collectEvidence(card, supporting);
        supporting.retainAll(allowedSources(ctx));
        validateCardText(card, crop, supporting, ctx.extraString("fieldRegion"));
    }

    private void validateCardText(
            Object value, String crop, Set<String> supporting, String region) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!"evidence".equals(entry.getKey()))
                    validateCardText(entry.getValue(), crop, supporting, region);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) validateCardText(item, crop, supporting, region);
        } else if (value instanceof String text) {
            PrescriptionEvidenceGuard.validateReply(text, crop, supporting, library, region);
        }
    }

    private Map<String, Object> sanitizePlanEvidence(
            Map<String, Object> plan, Set<String> allowed) {
        if (plan == null) return null;
        Object sanitized = AgriTools.sanitizeEvidenceContainers(plan, allowed, new int[1]);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sanitized;
        return result;
    }

    private String sanitizeCitations(String reply, Set<String> allowed) {
        if (reply == null || reply.isBlank()) return reply;
        java.util.regex.Matcher matcher = SOURCE_ID_RE.matcher(reply);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group();
            matcher.appendReplacement(
                    out,
                    java.util.regex.Matcher.quoteReplacement(
                            allowed.contains(id) ? id : "（来源ID未在本次检索结果中）"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean isOnlyCardReceipt(String reply) {
        if (reply == null || reply.isBlank()) return true;
        String plain = reply.replaceAll("[ \\t\\r*`#]", "");
        if (plain.length() > 160) return false;
        return java.util.Arrays.stream(plain.split("[，,。！？!?；;：:\\n]+"))
                .filter(part -> !part.isBlank())
                .allMatch(part -> CARD_RECEIPT_PART.matcher(part).matches());
    }

    public static boolean looksLikeOralClarify(String reply) {
        return reply != null && ORAL_CLARIFY_RE.matcher(reply).find();
    }
}
