package com.nongxin.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.task.FarmTask;
import com.nongxin.domain.task.TaskRecord;
import com.nongxin.domain.task.TaskStatus;
import com.nongxin.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcTaskRepository implements TaskRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcTaskRepository.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private final RowMapper<FarmTask> taskMapper =
            (rs, row) ->
                    new FarmTask(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("task_date"),
                            rs.getString("field_id"),
                            rs.getString("field_name"),
                            rs.getString("condition_text"),
                            rs.getString("method"),
                            rs.getString("review"),
                            rs.getString("note"),
                            rs.getString("status"),
                            null,
                            rs.getString("time_window"),
                            rs.getString("materials"),
                            rs.getString("risk"),
                            readEvidence(rs.getString("evidence")),
                            List.of(),
                            rs.getString("plan_item_id"),
                            rs.getString("source_message_id"),
                            rs.getString("created_at"),
                            rs.getString("updated_at"),
                            rs.getString("confirmed_at"),
                            rs.getString("executed_at"),
                            rs.getString("completed_at"),
                            List.of());

    private static final RowMapper<TaskRecord> RECORD_MAPPER =
            (rs, row) ->
                    new TaskRecord(
                            rs.getString("id"),
                            rs.getString("task_id"),
                            rs.getString("field_id"),
                            rs.getString("kind"),
                            rs.getString("record_date"),
                            rs.getString("note"),
                            rs.getString("outcome"),
                            rs.getString("source_message_id"),
                            rs.getString("created_at"));

    public JdbcTaskRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<FarmTask> list(String userId) {
        return jdbc.query(
                "SELECT * FROM farm_tasks WHERE user_id=? ORDER BY task_date ASC, created_at ASC,"
                        + " id ASC",
                taskMapper,
                userId);
    }

    @Override
    public FarmTask find(String userId, String id) {
        List<FarmTask> rows =
                jdbc.query(
                        "SELECT * FROM farm_tasks WHERE id=? AND user_id=?",
                        taskMapper,
                        id,
                        userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public List<TaskRecord> records(String userId, String taskId) {
        return jdbc.query(
                "SELECT * FROM task_records WHERE task_id=? AND EXISTS (SELECT 1 FROM farm_tasks t"
                    + " WHERE t.id=task_records.task_id AND t.user_id=?) ORDER BY record_date ASC,"
                    + " created_at ASC, id ASC",
                RECORD_MAPPER,
                taskId,
                userId);
    }

    @Override
    public List<FarmTask> forField(String userId, String fieldId, int limit) {
        return jdbc.query(
                "SELECT * FROM farm_tasks WHERE field_id=? AND user_id=?"
                        + " ORDER BY task_date DESC, created_at DESC, id DESC LIMIT ?",
                taskMapper,
                fieldId,
                userId,
                limit);
    }

    @Override
    public List<TaskRecord> allRecords(String userId, String fieldId) {
        String guard =
                " AND EXISTS (SELECT 1 FROM farm_tasks t WHERE t.id=task_records.task_id AND"
                        + " t.user_id=?)";
        return fieldId == null
                ? jdbc.query(
                        "SELECT * FROM task_records WHERE 1=1"
                                + guard
                                + " ORDER BY record_date ASC, created_at ASC, id ASC",
                        RECORD_MAPPER,
                        userId)
                : jdbc.query(
                        "SELECT * FROM task_records WHERE field_id=?"
                                + guard
                                + " ORDER BY record_date ASC, created_at ASC, id ASC",
                        RECORD_MAPPER,
                        fieldId,
                        userId);
    }

    @Override
    public void create(String userId, FarmTask task, TaskStatus status, String now) {
        jdbc.update(
                "INSERT INTO farm_tasks"
                    + " (id,title,task_date,field_id,field_name,condition_text,method,review,note,"
                    + "status,time_window,materials,risk,evidence,plan_item_id,source_message_id,created_at,updated_at,confirmed_at,executed_at,completed_at,user_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.id(),
                task.title(),
                task.date(),
                task.fieldId(),
                task.fieldName(),
                task.condition(),
                task.method(),
                task.review(),
                task.note(),
                status.code(),
                task.timeWindow(),
                task.materials(),
                task.risk(),
                writeEvidence(task.evidence()),
                task.planItemId(),
                task.sourceMessageId(),
                task.createdAt(),
                now,
                status == TaskStatus.PENDING ? now : null,
                null,
                null,
                userId);
    }

    @Override
    public FarmTask findByPlanItem(String userId, String sourceMessageId, String planItemId) {
        List<FarmTask> rows =
                jdbc.query(
                        "SELECT * FROM farm_tasks WHERE source_message_id=? AND plan_item_id=? AND"
                                + " user_id=?",
                        taskMapper,
                        sourceMessageId,
                        planItemId,
                        userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void update(String userId, String id, FarmTask task, String updatedAt) {
        jdbc.update(
                "UPDATE farm_tasks SET"
                    + " title=?,task_date=?,field_id=?,field_name=?,condition_text=?,method=?,"
                    + "review=?,note=?,time_window=?,materials=?,risk=?,evidence=?,plan_item_id=?,source_message_id=?,updated_at=?"
                    + " WHERE id=? AND user_id=?",
                task.title(),
                task.date(),
                task.fieldId(),
                task.fieldName(),
                task.condition(),
                task.method(),
                task.review(),
                task.note(),
                task.timeWindow(),
                task.materials(),
                task.risk(),
                writeEvidence(task.evidence()),
                task.planItemId(),
                task.sourceMessageId(),
                updatedAt,
                id,
                userId);
    }

    @Override
    public void changeStatus(
            String userId, String id, String status, String now, String confirmedAt) {
        jdbc.update(
                "UPDATE farm_tasks SET status=?, updated_at=?, confirmed_at=COALESCE(?,"
                        + " confirmed_at) WHERE id=? AND user_id=?",
                status,
                now,
                confirmedAt,
                id,
                userId);
    }

    @Override
    public void addRecord(String userId, String taskId, TaskRecord record) {
        if (find(userId, taskId) == null) throw new java.util.NoSuchElementException("任务不存在");
        jdbc.update(
                "INSERT INTO task_records"
                    + " (id,task_id,field_id,kind,record_date,note,outcome,source_message_id,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                record.id(),
                taskId,
                record.fieldId(),
                record.kind(),
                record.date(),
                record.note(),
                record.outcome(),
                record.sourceMessageId(),
                record.createdAt());
    }

    @Override
    public void advanceAfterRecord(String userId, String taskId, String target, String now) {
        jdbc.update(
                "UPDATE farm_tasks SET status=?, updated_at=?,"
                        + " confirmed_at=COALESCE(confirmed_at,?), executed_at=CASE WHEN"
                        + " ?='awaiting_review' THEN ? ELSE executed_at END, completed_at=CASE WHEN"
                        + " ?='completed' THEN ? ELSE completed_at END WHERE id=? AND user_id=?",
                target,
                now,
                now,
                target,
                now,
                target,
                now,
                taskId,
                userId);
    }

    @Override
    public void touch(String userId, String taskId, String now) {
        jdbc.update(
                "UPDATE farm_tasks SET updated_at=? WHERE id=? AND user_id=?", now, taskId, userId);
    }

    @Override
    public boolean delete(String userId, String id) {
        if (find(userId, id) == null) return false;
        jdbc.update("DELETE FROM task_records WHERE task_id=?", id);
        return jdbc.update("DELETE FROM farm_tasks WHERE id=? AND user_id=?", id, userId) > 0;
    }

    private List<String> readEvidence(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> ids = json.readValue(raw, new TypeReference<List<String>>() {});
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("[task] 依据字段解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private String writeEvidence(List<String> evidence) {
        try {
            return json.writeValueAsString(evidence == null ? List.of() : evidence);
        } catch (Exception e) {
            return "[]";
        }
    }
}
