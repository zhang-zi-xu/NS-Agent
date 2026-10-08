package com.nongxin.repository.jdbc;

import com.nongxin.repository.KnowledgeVectorRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class JdbcKnowledgeVectorRepository implements KnowledgeVectorRepository {
    private final JdbcTemplate jdbc;

    public JdbcKnowledgeVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT chunk_id, model, vector FROM kb_vectors");
    }

    @Override
    public void save(String chunkId, String model, int dimensions, String vectorJson) {
        jdbc.update(
                "INSERT OR REPLACE INTO kb_vectors (chunk_id, model, dim, vector, updated_at)"
                        + " VALUES (?,?,?,?,datetime('now','localtime'))",
                chunkId,
                model,
                dimensions,
                vectorJson);
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM kb_vectors");
    }
}
