package com.nongxin.service.chat;

import com.nongxin.domain.field.FieldProfile;
import com.nongxin.service.KnowledgeLibrary;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Narrow request intent and explicit field-region extraction, not an inferred farm profile. */
public final class AgriculturalRequestContext {
    private static final Pattern INFORMATION =
            Pattern.compile(
                    "(?:需要|要|应该).{0,20}(?:提供|准备|填写).{0,12}(?:哪些|什么).{0,20}(?:信息|资料|数据)|"
                            + "(?:需要|要).{0,12}(?:哪些|什么).{0,12}(?:田块信息|农情数据)");
    private static final Pattern FIELD_REGION =
            Pattern.compile(
                    "(?:田块|地块|农田|麦田|稻田|我的地)(?:的)?(?:所在地|位置)?(?:在|位于|是|：|:)\\s*([^，。；\\n]{1,30})");
    private static final Pattern UNKNOWN_REGION =
            Pattern.compile("(?:田块|地块|农田|麦田|稻田|我的地).{0,8}(?:不在|不是|不确定|不知道|地区未知)");
    private static final Pattern HYPOTHETICAL = Pattern.compile("如果|假如|假设|比如|例如|是否|假定");
    private static final Pattern REGION_ANSWER =
            Pattern.compile("问题：[^\\n]*(?:田块|地块)[^\\n]*(?:地区|哪里|位置)[^\\n]*\\n回答：([^\\n]+)");

    private AgriculturalRequestContext() {}

    public static boolean informationChecklist(String question) {
        return question != null && INFORMATION.matcher(question).find();
    }

    /** Device coordinates and assistant prose deliberately do not establish the field's region. */
    public static String confirmedRegion(List<Map<String, Object>> history, FieldProfile field) {
        for (int i = history.size() - 1; i >= 0; i--) {
            var message = history.get(i);
            if (!"user".equals(message.get("role"))) continue;
            String text = ChatInputPreparer.lastUserText(List.of(message));
            if (UNKNOWN_REGION.matcher(text).find()) return null;
            var match = FIELD_REGION.matcher(text);
            if (match.find()) {
                return explicitRegion(text, match);
            }
            var answer = REGION_ANSWER.matcher(text);
            if (answer.find()) return KnowledgeLibrary.regionOf(answer.group(1));
        }
        if (field != null && field.notes() != null) {
            if (UNKNOWN_REGION.matcher(field.notes()).find()) return null;
            var match = FIELD_REGION.matcher(field.notes());
            if (match.find()) return explicitRegion(field.notes(), match);
        }
        return null;
    }

    private static String explicitRegion(String text, java.util.regex.Matcher match) {
        String prefix = text.substring(Math.max(0, match.start() - 12), match.start());
        if (HYPOTHETICAL.matcher(prefix).find() || match.group(1).matches(".*[吗么？?].*"))
            return null;
        return KnowledgeLibrary.regionOf(match.group(1));
    }
}
