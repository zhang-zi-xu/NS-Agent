package com.nongxin.service;

import com.nongxin.domain.knowledge.KnowledgeRelation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 来源约束的农业图谱：保留元数据索引，并添加经原文核对的农业关系，不引入图数据库。
 *
 * <p>按问题中的具体实体关联来源，向量不可用时仍运行；不按作物名或同文档关系泛召回。 农业关系必须绑定已核验原文和精确定位，不能视为用户田块诊断或模型置信度。
 */
public interface KnowledgeGraphService {

    /** 图谱规模（节点/边） */
    Map<String, Object> stats();

    /**
     * 图谱扩展召回：基于查询实体与已命中片段，返回相邻片段 id（不含已命中项）。
     *
     * @param query 原始查询
     * @param crop 田块作物（用于实体聚焦）
     * @param hitChunkIds 已由关键词/向量路召回的片段
     * @param limit 最多返回条数
     */
    List<String> expand(String query, String crop, Set<String> hitChunkIds, int limit);

    /** Only expose relations backed by this request's retrieved sources. */
    default List<KnowledgeRelation> relations(Set<String> sourceIds, String crop) {
        return List.of();
    }
}
