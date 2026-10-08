package com.nongxin.service.knowledge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative vocabulary normalization. Expansion supplies search clues, not diagnoses. */
public final class AgriculturalQuery {
    private static final List<List<String>> ALIASES =
            List.of(
                    List.of("水稻", "稻子", "稻苗", "稻田"),
                    List.of("小麦", "麦子", "麦苗", "麦田"),
                    List.of("稻瘟病", "稻瘟"),
                    List.of("叶瘟", "叶稻瘟"),
                    List.of("穗瘟", "穗颈瘟"),
                    List.of("纹枯病", "纹枯"),
                    List.of("稻飞虱", "飞虱"),
                    List.of("二化螟"),
                    List.of("稻纵卷叶螟", "稻纵卷叶虫", "卷叶螟"),
                    List.of("赤霉病", "赤霉"),
                    List.of("条锈病", "条锈"),
                    List.of("蚜虫", "麦蚜", "穗蚜"),
                    List.of("洪涝", "被淹", "水淹", "淹了"),
                    List.of("积水", "排不掉水"),
                    List.of("灌溉", "浇水", "灌水"),
                    List.of("补喷", "补治", "重新喷", "重新打药"),
                    List.of("施药", "喷药", "打药"),
                    List.of("施肥", "上肥", "追肥"),
                    List.of("收获", "收割", "割麦"),
                    List.of("混配", "混用", "混着用"),
                    List.of("高温", "热天", "天太热", "天气太热"),
                    List.of("干热风"),
                    List.of("晒田", "烤田"));

    private static final Set<String> STOP =
            Set.of(
                    "水稻", "小麦", "通用", "防治", "防控", "管理", "技术", "措施", "方法", "注意", "工作", "情况", "发生",
                    "进行", "加强", "做好", "及时", "各地", "要点", "指导", "意见", "如何", "怎么", "什么", "时候", "需要",
                    "可以", "是否", "怎样", "为什么", "要求", "建议", "一下", "现在", "这个", "那个", "我们", "我的", "怎么办",
                    "应该", "田里", "作物", "种植", "农业", "用药", "病虫", "虫害", "病害", "病虫害", "不知", "知道", "预防");
    private static final Set<String> DOMAIN_ANCHORS =
            Set.of(
                    "灌浆水", "稳定性试验", "枯鞘", "束叶", "百株", "百丛", "百穗", "药害", "干旱", "穗肥", "氮肥", "抗药性",
                    "整地", "秸秆", "还田", "播种", "播前", "底肥", "基肥", "晒种");

    public static final List<String> STAGES =
            List.of(
                    "苗期", "返青", "分蘖", "拔节", "孕穗", "破口", "抽穗", "齐穗", "扬花", "灌浆", "蜡熟", "播前", "播种",
                    "越冬");

    public static boolean isPreparation(String query) {
        String text = normalize(query);
        if (List.of("苗期", "返青", "分蘖", "拔节", "孕穗", "抽穗", "扬花", "灌浆", "蜡熟").stream()
                .anyMatch(text::contains)) return false;
        return Pattern.compile("播前|备耕|整地|待播|尚未播|未播种|秸秆.{0,12}(粉碎|还田)").matcher(text).find();
    }

    public static boolean supportsPreparation(String heading, String stage, String text) {
        String metadata = (heading == null ? "" : heading) + (stage == null ? "" : stage);
        if (Pattern.compile("播前|备耕|整地|播种").matcher(metadata).find()) return true;
        return Pattern.compile("播种前|播前|秋播|前茬.{0,15}(收获|还田)|整地|深翻|基肥|底肥")
                .matcher(text == null ? "" : text)
                .find();
    }

    private static final Map<String, String> CANONICAL = vocabulary();
    private static final Pattern ALIAS_PATTERN =
            Pattern.compile(
                    CANONICAL.keySet().stream()
                            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                            .map(Pattern::quote)
                            .collect(java.util.stream.Collectors.joining("|")));

    private AgriculturalQuery() {}

    public static String normalize(String text) {
        String value = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        // One pass, including canonical tokens: no "稻瘟病病" or "稻稻飞虱" from substring replacements.
        return ALIAS_PATTERN
                .matcher(value)
                .replaceAll(
                        match ->
                                java.util.regex.Matcher.quoteReplacement(
                                        CANONICAL.get(match.group())));
    }

    private static Map<String, String> vocabulary() {
        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> group : ALIASES)
            for (String alias : group) out.put(alias, group.getFirst());
        return Map.copyOf(out);
    }

    public static Set<String> terms(String text) {
        String value = searchText(text);
        Set<String> terms = new LinkedHashSet<>();
        for (String segment : value.split("[^\\p{IsHan}]+")) {
            if (segment.length() < 2) continue;
            if (segment.length() <= 12 && !STOP.contains(segment)) terms.add(segment);
            for (int i = 0; i + 2 <= segment.length(); i++) {
                String term = segment.substring(i, i + 2);
                if (!STOP.contains(term)) terms.add(term);
            }
        }
        for (List<String> group : ALIASES) {
            String canonical = group.getFirst();
            if (!STOP.contains(canonical) && value.contains(canonical)) terms.add(canonical);
        }
        return terms;
    }

    public static String searchText(String text) {
        return normalize(text).replace("水稻", " ").replace("小麦", " ");
    }

    public static boolean informativeEntity(String name) {
        return name != null
                && name.length() >= 2
                && !STOP.contains(name)
                && !STAGES.contains(name)
                && !"全生育期".equals(name);
    }

    public static boolean bodyAnchor(String term) {
        return DOMAIN_ANCHORS.contains(term)
                || STAGES.contains(term)
                || (CANONICAL.containsValue(term) && !STOP.contains(term));
    }

    public static String crop(String text) {
        String value = normalize(text);
        boolean rice = value.contains("水稻"), wheat = value.contains("小麦");
        return rice == wheat ? null : rice ? "水稻" : "小麦";
    }
}
