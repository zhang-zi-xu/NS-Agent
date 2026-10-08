package com.nongxin.repository.jdbc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.nongxin.repository.UploadRepository;
import com.nongxin.service.UploadService.Stored;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionStatus;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

@Repository
public class JdbcUploadRepository implements UploadRepository {
    private static final Pattern IMAGE_ID_IN_TEXT = Pattern.compile("img-[A-Za-z0-9_-]+");
    private static final ObjectReader RETENTION_JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final RowMapper<Stored> MAPPER =
            (rs, row) ->
                    new Stored(
                            rs.getString("id"),
                            rs.getString("mime"),
                            rs.getString("ext"),
                            rs.getLong("bytes"),
                            rs.getInt("width"),
                            rs.getInt("height"),
                            rs.getString("sha256"),
                            rs.getString("created_at"),
                            rs.getString("referenced_at"),
                            rs.getString("field_id"),
                            rs.getString("observed_at"),
                            rs.getString("note"),
                            rs.getString("task_id"));
    private final JdbcTemplate jdbc;
    private final JdbcTransactionSupport transactions;

    public JdbcUploadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transactions = new JdbcTransactionSupport(jdbc);
    }

    @Override
    public boolean hasBoundConnection() {
        return transactions.hasBoundConnection();
    }

    @Override
    public <T> T inTransaction(Function<TransactionStatus, T> operation) {
        return transactions.execute(operation);
    }

    @Override
    public Integer countByIdForPublication(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id);
    }

    @Override
    public int insert(String userId, Stored photo) {
        return jdbc.update(
                "INSERT INTO uploads"
                    + " (id,mime,ext,bytes,width,height,sha256,created_at,referenced_at,field_id,observed_at,note,task_id,user_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,NULL,?,?,?,?,?)",
                photo.id(),
                photo.mime(),
                photo.ext(),
                photo.bytes(),
                photo.width(),
                photo.height(),
                photo.sha256(),
                photo.createdAt(),
                photo.fieldId(),
                photo.observedAt(),
                photo.note(),
                photo.taskId(),
                userId);
    }

    @Override
    public List<Stored> byField(String userId, String fieldId, int limit) {
        return jdbc.query(
                "SELECT * FROM uploads WHERE field_id=? AND user_id=? ORDER BY"
                        + " COALESCE(observed_at, created_at) DESC, created_at DESC LIMIT ?",
                MAPPER,
                fieldId,
                userId,
                limit);
    }

    @Override
    public Map<String, Object> usage(String userId, String fieldId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT COUNT(*) AS photos, COALESCE(SUM(bytes),0) AS bytes FROM uploads"
                                + " WHERE field_id=? AND user_id=?",
                        fieldId,
                        userId);
        return Map.of("photos", row.get("photos"), "bytes", row.get("bytes"));
    }

    @Override
    public Stored find(String userId, String id) {
        List<Stored> rows =
                jdbc.query("SELECT * FROM uploads WHERE id=? AND user_id=?", MAPPER, id, userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public int markReferenced(String userId, String id, String now) {
        return jdbc.update(
                "UPDATE uploads SET referenced_at=? WHERE id=? AND referenced_at IS NULL AND"
                        + " user_id=?",
                now,
                id,
                userId);
    }

    @Override
    public int pinReference(String userId, String id, String now) {
        return jdbc.update(
                "UPDATE uploads SET referenced_at=COALESCE(referenced_at,?) WHERE id=? AND"
                        + " user_id=?",
                now,
                id,
                userId);
    }

    @Override
    public int delete(String userId, String id) {
        return jdbc.update("DELETE FROM uploads WHERE id=? AND user_id=?", id, userId);
    }

    @Override
    public int updateArchive(
            String userId, String id, String note, String observedAt, String fieldId) {
        return jdbc.update(
                "UPDATE uploads SET note=?, observed_at=?, field_id=? WHERE id=? AND user_id=?",
                note,
                observedAt,
                fieldId,
                id,
                userId);
    }

    @Override
    public List<Stored> staleCandidates(String cutoff) {
        return jdbc.query(
                "SELECT * FROM uploads WHERE referenced_at IS NULL"
                        + " AND field_id IS NULL AND task_id IS NULL AND created_at < ?",
                MAPPER,
                cutoff);
    }

    @Override
    public List<Stored> findForMaintenance(String id) {
        return jdbc.query("SELECT * FROM uploads WHERE id=?", MAPPER, id);
    }

    @Override
    public int deleteForMaintenance(String id) {
        return jdbc.update("DELETE FROM uploads WHERE id=?", id);
    }

    @Override
    public List<Stored> list(String userId) {
        return jdbc.query(
                "SELECT * FROM uploads WHERE user_id=? ORDER BY created_at DESC", MAPPER, userId);
    }

    @Override
    public Integer ownedFieldCount(String userId, String fieldId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fields WHERE id=? AND user_id=?",
                Integer.class,
                fieldId,
                userId);
    }

    @Override
    public Integer ownedTaskCount(String userId, String taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM farm_tasks WHERE id=? AND user_id=?",
                Integer.class,
                taskId,
                userId);
    }

    @Override
    public Set<String> referencedByConversations(Set<String> candidates) {
        Set<String> referenced = new HashSet<>();
        // No CurrentUser filter: legacy or cross-owner references also protect data from automatic
        // deletion.
        jdbc.query(
                "SELECT messages_json FROM conversations",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        row -> {
                            JsonNode messages;
                            try {
                                messages = RETENTION_JSON.readTree(row.getString(1));
                            } catch (JsonProcessingException | IllegalArgumentException e) {
                                throw new SQLException("Invalid saved conversation data");
                            }
                            if (messages == null || !messages.isArray())
                                throw new SQLException("Unknown saved conversation structure");
                            for (JsonNode message : messages) {
                                if (!message.isObject())
                                    throw new SQLException("Unknown saved message structure");
                                JsonNode images = message.get("images");
                                if (images != null && !images.isNull()) {
                                    if (!images.isArray())
                                        throw new SQLException("Unknown saved image structure");
                                    for (JsonNode image : images) {
                                        if (!image.isObject()
                                                || !image.path("id").isTextual()
                                                || image.path("id").asText().isBlank()) {
                                            throw new SQLException("Unknown saved image reference");
                                        }
                                    }
                                }
                            }
                            // Also retain IDs in snapshots or inline links/text. False positives
                            // only retain files.
                            var pending = new ArrayDeque<JsonNode>();
                            pending.push(messages);
                            while (!pending.isEmpty()) {
                                JsonNode node = pending.pop();
                                if (node.isTextual()) {
                                    String text = node.textValue();
                                    if (candidates.contains(text)) referenced.add(text);
                                    var ids = IMAGE_ID_IN_TEXT.matcher(text);
                                    while (ids.find())
                                        if (candidates.contains(ids.group()))
                                            referenced.add(ids.group());
                                } else if (node.isContainerNode())
                                    node.elements().forEachRemaining(pending::push);
                            }
                        });
        return referenced;
    }
}
