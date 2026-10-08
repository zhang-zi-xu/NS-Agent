package com.nongxin.repository.jdbc;

import com.nongxin.domain.field.FieldProfile;
import com.nongxin.domain.field.FieldRecord;
import com.nongxin.repository.FieldRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcFieldRepository implements FieldRepository {
    private static final RowMapper<FieldRecord> RECORD_MAPPER =
            (rs, row) -> new FieldRecord(rs.getString("record_date"), rs.getString("note"));
    private final JdbcTemplate jdbc;

    public JdbcFieldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FieldProfile> list(String userId) {
        return jdbc.query(
                "SELECT * FROM fields WHERE user_id=? ORDER BY created_at DESC",
                (rs, row) -> read(rs),
                userId);
    }

    @Override
    public FieldProfile find(String userId, String id) {
        List<FieldProfile> rows =
                jdbc.query(
                        "SELECT * FROM fields WHERE id = ? AND user_id=?",
                        (rs, row) -> read(rs),
                        id,
                        userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void create(String userId, String id, FieldProfile field) {
        jdbc.update(
                "INSERT INTO fields (id, name, crop, variety, sow_date, area_mu, notes, user_id)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                id,
                field.name(),
                field.crop(),
                field.variety(),
                field.sowDate(),
                field.areaMu(),
                field.notes(),
                userId);
    }

    @Override
    public int update(String userId, String id, FieldProfile field) {
        return jdbc.update(
                "UPDATE fields SET name=?, crop=?, variety=?, sow_date=?, area_mu=?, notes=? WHERE"
                        + " id=? AND user_id=?",
                field.name(),
                field.crop(),
                field.variety(),
                field.sowDate(),
                field.areaMu(),
                field.notes(),
                id,
                userId);
    }

    @Override
    public boolean delete(String userId, String id) {
        // Verify ownership before changing dependents, including connections without SQLite
        // foreign-key enforcement.
        if (find(userId, id) == null) return false;
        jdbc.update("DELETE FROM field_records WHERE field_id = ?", id);
        jdbc.update("UPDATE farm_tasks SET field_id = NULL WHERE field_id = ?", id);
        jdbc.update("UPDATE conversations SET field_id = NULL WHERE field_id = ?", id);
        return jdbc.update("DELETE FROM fields WHERE id = ? AND user_id=?", id, userId) > 0;
    }

    @Override
    public void addRecord(String userId, String fieldId, String date, String note) {
        if (find(userId, fieldId) == null)
            throw new java.util.NoSuchElementException("田块不存在或不属于当前用户");
        jdbc.update(
                "INSERT INTO field_records (field_id, record_date, note) VALUES (?,?,?)",
                fieldId,
                date,
                note);
    }

    private FieldProfile read(ResultSet rs) throws SQLException {
        List<FieldRecord> records =
                jdbc.query(
                        "SELECT record_date, note FROM field_records WHERE field_id = ? ORDER BY"
                                + " record_date ASC, id ASC",
                        RECORD_MAPPER,
                        rs.getString("id"));
        Double area =
                rs.getObject("area_mu") instanceof Number number ? number.doubleValue() : null;
        return new FieldProfile(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("crop"),
                rs.getString("variety"),
                rs.getString("sow_date"),
                area,
                rs.getString("notes"),
                records);
    }
}
