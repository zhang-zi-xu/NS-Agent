package com.nongxin.repository.jdbc;

import com.nongxin.repository.AnswerCacheRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class JdbcAnswerCacheRepository implements AnswerCacheRepository {
    private final JdbcTemplate jdbc;

    public JdbcAnswerCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> find(String cacheKey) {
        return jdbc.queryForList(
                "SELECT reply, plan_json, risk_json, clarify_json, cached_at FROM answer_cache"
                        + " WHERE cache_key = ?",
                cacheKey);
    }

    @Override
    public void delete(String cacheKey) {
        jdbc.update("DELETE FROM answer_cache WHERE cache_key = ?", cacheKey);
    }

    @Override
    public void recordHit(String cacheKey) {
        jdbc.update(
                "UPDATE answer_cache SET hit_count = hit_count + 1 WHERE cache_key = ?", cacheKey);
    }

    @Override
    public void save(
            String cacheKey,
            String reply,
            String planJson,
            String riskJson,
            String clarifyJson,
            String cachedAt) {
        jdbc.update(
                "INSERT OR REPLACE INTO answer_cache (cache_key, reply, plan_json, risk_json,"
                    + " clarify_json, cached_at, hit_count) VALUES (?,?,?,?,?,?, COALESCE((SELECT"
                    + " hit_count FROM answer_cache WHERE cache_key = ?), 0))",
                cacheKey,
                reply,
                planJson,
                riskJson,
                clarifyJson,
                cachedAt,
                cacheKey);
    }

    @Override
    public int evictBefore(String before) {
        return jdbc.update("DELETE FROM answer_cache WHERE cached_at < ?", before);
    }

    @Override
    public Integer entryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM answer_cache", Integer.class);
    }

    @Override
    public Integer hitCount() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(hit_count),0) FROM answer_cache", Integer.class);
    }
}
