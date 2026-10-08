package com.nongxin.service;

import com.nongxin.domain.knowledge.KnowledgeChunk;
import com.nongxin.domain.knowledge.KnowledgeDocument;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 可信农技资料库：来源登记 + 原文片段。默认使用本地词法与来源约束图谱，按作物、地区及原文核验状态排序； 向量服务可用时增加语义召回并做 RRF 融合。{@link
 * #searchKeywordOnly} 保留旧关键词实现作为评测对照。 模型只能引用本次检索命中的 chunkId，由 {@link #resolve} 校验。
 */
public interface KnowledgeLibrary {

    /** 已登记地区 → 位置关键词。只覆盖有资料的地区；猜不到就不过滤，由来源卡显示适用地区。 */
    Map<String, List<String>> REGION_HINTS =
            Map.of(
                    "湖北", List.of("湖北"),
                    "浙江",
                            List.of(
                                    "浙江", "杭州", "宁波", "温州", "嘉兴", "绍兴", "台州", "金华", "湖州", "丽水",
                                    "衢州", "舟山"),
                    "河南",
                            List.of(
                                    "河南", "郑州", "开封", "洛阳", "南阳", "周口", "商丘", "驻马店", "信阳", "许昌",
                                    "新乡"));

    /** 地区层级：请求地区可以接受哪些资料地区（全国性资料始终可用）。 河南属于黄淮麦区，浙江属于长江中下游稻区，否则本地资料会被自己的过滤规则挡掉。 */
    Map<String, List<String>> REGION_ALIASES =
            Map.of(
                    "浙江", List.of("浙江", "长江中下游"),
                    "河南", List.of("河南", "黄淮"),
                    "长江中下游", List.of("长江中下游", "浙江"),
                    "黄淮", List.of("黄淮", "河南"));

    /** 把请求里的位置文本映射到资料库地区。 */
    static String regionOf(String locationLabel) {
        if (locationLabel == null || locationLabel.isBlank()) return null;
        for (Map.Entry<String, List<String>> entry : REGION_HINTS.entrySet()) {
            for (String hint : entry.getValue()) {
                if (locationLabel.contains(hint)) return entry.getKey();
            }
        }
        return null;
    }

    /** 片段地区是否可用于该请求地区；片段地区为全国或未标注时始终可用。 */
    static boolean regionUsable(String requestRegion, String chunkRegion) {
        if (chunkRegion == null || chunkRegion.isBlank() || chunkRegion.contains("全国")) return true;
        if (requestRegion == null || requestRegion.isBlank()) return true;
        return REGION_ALIASES.getOrDefault(requestRegion, List.of(requestRegion)).stream()
                .anyMatch(chunkRegion::contains);
    }

    /** 命中片段 + 所属文档 + 相关度分数。 */
    record SourcedHit(KnowledgeChunk chunk, KnowledgeDocument document, int score) {}

    /** 全部来源文档（含尚未核验的本地草稿），按核验状态优先排序。 */
    List<KnowledgeDocument> documents();

    /** 全部片段（测试与统计用）。 */
    List<KnowledgeChunk> chunks();

    /**
     * 词法、图谱与可选向量检索 + 作物边界与适用性排序。
     *
     * @param crop 田块作物；非空时只召回该作物与通用片段，避免把别的作物资料当依据
     * @param region 请求地区；优先适用地区与全国性片段，异地资料仍可作为带限制的参考
     */
    List<SourcedHit> search(String query, String crop, String region, int topK);

    /** 旧版关键词检索，用于同深度评估对照；不是当前本地检索路径。 */
    List<SourcedHit> searchKeywordOnly(String query, String crop, int topK);

    /** 命中片段 → 供模型使用的文本，每条都带来源 ID、机构、日期与适用条件。 */
    String formatForModel(List<SourcedHit> hits);

    /** Source applicability cautions without inventing a field location or growth stage. */
    default String retrievalGuidance(
            List<SourcedHit> hits, String query, String crop, String region) {
        return "";
    }

    /** 按 chunkId 校验引用：只返回确实存在于资料库中的片段。 */
    List<SourcedHit> resolve(Collection<String> chunkIds);

    /** 从问题文本里识别已登记作物。 用途：用户可能在当前田块（比如水稻）下问另一个作物（比如小麦）的问题， 此时应按问题中提到的作物检索，而不是被田块作物硬过滤挡掉。 */
    String detectCrop(String query);

    /** 来源ID → 前端来源卡（只输出资料库真实存在的片段）。 对话响应与保存会话都走这里，客户端无法伪造状态或链接。 */
    List<Map<String, Object>> cards(Collection<String> chunkIds);
}
