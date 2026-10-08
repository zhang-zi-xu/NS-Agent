package com.nongxin.repository;

import java.util.List;
import java.util.Map;

/** Persistence only. This does not opt chat traffic into the currently unconnected cache. */
public interface AnswerCacheRepository {
    List<Map<String, Object>> find(String cacheKey);

    void delete(String cacheKey);

    void recordHit(String cacheKey);

    void save(
            String cacheKey,
            String reply,
            String planJson,
            String riskJson,
            String clarifyJson,
            String cachedAt);

    int evictBefore(String before);

    Integer entryCount();

    Integer hitCount();
}
