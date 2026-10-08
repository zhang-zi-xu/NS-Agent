package com.nongxin.repository;

import java.util.List;
import java.util.Map;

public interface KnowledgeVectorRepository {
    List<Map<String, Object>> list();

    void save(String chunkId, String model, int dimensions, String vectorJson);

    void clear();
}
