package com.nongxin.service.knowledge;

import com.nongxin.service.KnowledgeLibrary;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Narrow deterministic numeric-prescription gate, not a semantic truth or pesticide-registration
 * checker.
 */
public final class PrescriptionEvidenceGuard {
    private static final Pattern ACTION =
            Pattern.compile("施药|喷药|打药|喷施|施肥|追肥|补肥|底肥|基肥|灌溉|浇水|灌水|整地|深翻|旋耕|播种|播量|播深|拌种|包衣|晒种");
    private static final Pattern QUANTITY =
            Pattern.compile(
                    "(?<![\\d.])\\d+(?:\\.\\d+)?(?:[-—~至]\\d+(?:\\.\\d+)?)?(?:公斤|千克|毫升|毫克|厘米|克|升|%|倍)");
    private static final List<String> PRODUCTS =
            List.of(
                    "磷酸二氢钾", "过磷酸钙", "尿素", "氯化钾", "复合肥", "复混肥", "氮肥", "磷肥", "钾肥", "纯氮", "三环唑",
                    "吡虫啉", "噻虫嗪", "阿维菌素");

    private static final List<String> ITEM_TEXT_FIELDS =
            List.of("task", "materials", "method", "condition", "warning", "review", "window");

    private PrescriptionEvidenceGuard() {}

    public static void validate(
            Map<String, Object> plan, Set<String> allowed, KnowledgeLibrary library) {
        validate(plan, allowed, library, null, false);
    }

    /** For a field prescription, local numeric guidance needs an explicitly confirmed region. */
    public static void validate(
            Map<String, Object> plan,
            Set<String> allowed,
            KnowledgeLibrary library,
            String fieldRegion) {
        validate(plan, allowed, library, fieldRegion, true);
    }

    private static void validate(
            Map<String, Object> plan,
            Set<String> allowed,
            KnowledgeLibrary library,
            String fieldRegion,
            boolean checkRegion) {
        String declaredCrop = plan.get("crop") instanceof String c ? c.trim() : null;
        String crop = AgriculturalQuery.crop(declaredCrop);
        if (!(plan.get("items") instanceof List<?> items)) return;
        // Preserve other registered crops; missing/ambiguous crop labels must not bypass the gate.
        if (crop == null
                && declaredCrop != null
                && !"通用".equals(declaredCrop)
                && library.chunks().stream().anyMatch(chunk -> declaredCrop.equals(chunk.crop())))
            return;
        for (Object value : items) {
            if (!(value instanceof Map<?, ?> item)) continue;
            String text =
                    ITEM_TEXT_FIELDS.stream()
                            .map(item::get)
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .collect(java.util.stream.Collectors.joining("；"));
            if (!ACTION.matcher(text).find()) continue;
            List<String> cited =
                    item.get("evidence") instanceof List<?> ids
                            ? ids.stream()
                                    .filter(String.class::isInstance)
                                    .map(String.class::cast)
                                    .filter(allowed::contains)
                                    .toList()
                            : List.of();
            var sources =
                    library.resolve(cited).stream()
                            .filter(h -> h.document() != null && h.document().verified())
                            // Generic-label harvested paragraphs may mix crop-specific numbers.
                            // Quantitative prescriptions require an explicitly matching crop slice.
                            .filter(h -> crop != null && crop.equals(h.chunk().crop()))
                            .filter(
                                    h ->
                                            !checkRegion
                                                    || numericRegionUsable(
                                                            fieldRegion, h.chunk().region()))
                            .toList();
            // Each field retains its own nearby product/measurement context. Concatenating an
            // unrelated material with a method must not authorise its number accidentally.
            for (String field : ITEM_TEXT_FIELDS) {
                if (!(item.get(field) instanceof String fieldText)) continue;
                validateText(fieldText, crop, text, sources);
            }
        }
        for (String field : List.of("title", "summary")) {
            if (plan.get(field) instanceof String text)
                validateReply(text, crop, allowed, library, fieldRegion, checkRegion);
        }
    }

    /** Quantified agricultural recommendations in prose cannot bypass the structured-card gate. */
    public static void validateReply(
            String reply, String crop, Set<String> allowed, KnowledgeLibrary library) {
        validateReply(reply, crop, allowed, library, null, false);
    }

    public static void validateReply(
            String reply,
            String crop,
            Set<String> allowed,
            KnowledgeLibrary library,
            String fieldRegion) {
        validateReply(reply, crop, allowed, library, fieldRegion, true);
    }

    private static void validateReply(
            String reply,
            String crop,
            Set<String> allowed,
            KnowledgeLibrary library,
            String fieldRegion,
            boolean checkRegion) {
        if (reply == null || reply.isBlank()) return;
        var sources = replySources(crop, allowed, library, fieldRegion, checkRegion);
        for (String sentence : reply.split("[。！？\\n]")) {
            if (ACTION.matcher(sentence).find()) validateText(sentence, crop, sentence, sources);
        }
    }

    /**
     * 处方被依据核查拒绝后对正文的加严核查：不要求句中出现行动动词，只要出现数量就必须有同作物已核验原文支持。
     * 用于防止"被拒方案里的用量换个说法又出现在正文里"；天气实况与降水概率仍按原有豁免处理。
     */
    public static void validateReplyStrict(
            String reply,
            String crop,
            Set<String> allowed,
            KnowledgeLibrary library,
            String fieldRegion) {
        if (reply == null || reply.isBlank()) return;
        var sources = replySources(crop, allowed, library, fieldRegion, true);
        for (String sentence : reply.split("[。！？\\n]")) {
            if (QUANTITY.matcher(compact(sentence)).find())
                validateText(sentence, crop, sentence, sources);
        }
    }

    private static List<KnowledgeLibrary.SourcedHit> replySources(
            String crop,
            Set<String> allowed,
            KnowledgeLibrary library,
            String fieldRegion,
            boolean checkRegion) {
        return library.resolve(allowed).stream()
                .filter(h -> h.document() != null && h.document().verified())
                .filter(h -> crop != null && crop.equals(h.chunk().crop()))
                .filter(h -> !checkRegion || numericRegionUsable(fieldRegion, h.chunk().region()))
                .toList();
    }

    private static void validateText(
            String fieldText,
            String crop,
            String actionText,
            List<KnowledgeLibrary.SourcedHit> sources) {
        String prescription = compact(fieldText);
        var quantities = QUANTITY.matcher(prescription);
        while (quantities.find()) {
            String quantity = quantities.group();
            String product = nearestProduct(prescription, quantities.start(), quantities.end());
            // An incidence percentage is not a concentration. Unknown materials fail closed
            // instead of borrowing an unrelated numeric value from the same source.
            String context = nearby(prescription, quantities.start(), quantities.end());
            // Weather measurements are supplied separately, not agricultural prescriptions.
            String before =
                    prescription.substring(
                            Math.max(0, quantities.start() - 18), quantities.start());
            if (quantity.endsWith("%") && before.matches(".*(降水概率|降雨概率|空气湿度|相对湿度)[为约：:]*"))
                continue;
            if (crop == null)
                throw new IllegalArgumentException("数值处方必须明确单一作物，不能以未知或通用作物绕过原文依据核查。");
            boolean soil = context.contains("含水量") || context.contains("持水量");
            boolean seedRate = context.contains("播量") || context.contains("播种量");
            boolean requiresProduct = !quantity.endsWith("厘米") && !soil && !seedRate;
            boolean supported =
                    (!requiresProduct || product != null)
                            && sources.stream()
                                    .anyMatch(
                                            hit ->
                                                    supports(
                                                            compact(hit.chunk().text()),
                                                            quantity,
                                                            product,
                                                            soil,
                                                            seedRate,
                                                            actionText,
                                                            context));
            if (!supported)
                throw new IllegalArgumentException(
                        "具体药肥用量、灌水深度或作业数值未得到该方案项已核验、同作物原文支持。请移除数值处方，解释缺失依据并提供核查步骤；不得把草稿、别的作物、别的物料或别的作业条件套入。");
        }
    }

    private static boolean supports(
            String source,
            String quantity,
            String product,
            boolean soil,
            boolean seedRate,
            String actionText,
            String inputContext) {
        var matcher =
                Pattern.compile("(?<![\\d.])" + Pattern.quote(quantity) + "(?![\\d.])")
                        .matcher(source);
        while (matcher.find()) {
            String context = nearby(source, matcher.start(), matcher.end());
            if (soil) {
                if (!context.contains("含水量") && !context.contains("持水量")) continue;
                // The exported 2026-10-03 answer borrowed a sowing optimum for tillage.
                // Same digits/units do not prove the same operation or measurement purpose.
                boolean sourceSowing = context.contains("播种");
                boolean sourceTillage = context.matches(".*(整地|深翻|旋耕|耕地).*");
                boolean forTillage = actionText.matches("(?s).*(整地|深翻|旋耕).*");
                if (forTillage && sourceSowing && !sourceTillage) continue;
            }
            if (seedRate && !context.contains("播量") && !context.contains("播种量")) continue;
            if (quantity.endsWith("厘米")) {
                if (inputContext.matches(".*(水层|水深|灌水).*") && !context.matches(".*(水层|水深|灌水).*"))
                    continue;
                if (inputContext.contains("秸秆") && !context.contains("秸秆")) continue;
                if (inputContext.matches(".*(播深|播种深度).*") && !context.matches(".*(播深|播种深度).*"))
                    continue;
            }
            if (product == null
                    || product.equals(nearestProduct(source, matcher.start(), matcher.end())))
                return true;
        }
        return false;
    }

    private static String nearby(String text, int start, int end) {
        return text.substring(Math.max(0, start - 18), Math.min(text.length(), end + 22));
    }

    private static boolean numericRegionUsable(String fieldRegion, String sourceRegion) {
        if (sourceRegion != null && sourceRegion.contains("全国")) return true;
        return fieldRegion != null
                && !fieldRegion.isBlank()
                && sourceRegion != null
                && !sourceRegion.isBlank()
                && KnowledgeLibrary.regionUsable(fieldRegion, sourceRegion);
    }

    private static String nearestProduct(String text, int start, int end) {
        String nearest = null;
        int distance = 13;
        for (String product : PRODUCTS) {
            for (int offset = text.indexOf(product);
                    offset >= 0;
                    offset = text.indexOf(product, offset + product.length())) {
                int current =
                        offset >= end
                                ? offset - end
                                : Math.max(0, start - offset - product.length());
                if (current < distance) {
                    distance = current;
                    nearest = product;
                }
            }
        }
        return nearest;
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "")
                .replace('％', '%')
                .replace('～', '—')
                .replace('-', '—')
                .replace('~', '—')
                .replace('至', '—')
                .replaceAll("(\\d+(?:\\.\\d+)?)%—(?=\\d+(?:\\.\\d+)?%)", "$1—");
    }
}
