package com.nongxin.repository.jdbc;

import com.nongxin.domain.reminder.TaskReminder;
import com.nongxin.repository.TaskReminderRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Repository
public class JdbcTaskReminderRepository implements TaskReminderRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private static final RowMapper<TaskReminder> ROW =
            (rs, n) ->
                    new TaskReminder(
                            rs.getString("owner_id"),
                            rs.getString("task_id"),
                            rs.getString("bot_id"),
                            rs.getString("peer_id"),
                            rs.getLong("remind_at"),
                            rs.getLong("version"),
                            rs.getString("state"),
                            rs.getInt("attempts"),
                            rs.getLong("next_attempt_at"),
                            rs.getString("batch_id"));
    private static final String OPEN_TASK =
            " EXISTS (SELECT 1 FROM farm_tasks t WHERE t.id=task_id AND t.user_id=owner_id AND"
                    + " t.status NOT IN ('completed','cancelled'))";

    public JdbcTaskReminderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transaction =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())));
    }

    public List<TaskReminder> list(String owner) {
        return jdbc.query(
                "SELECT * FROM wechat_task_reminders WHERE owner_id=? ORDER BY remind_at, task_id",
                ROW,
                owner);
    }

    public TaskReminder find(String owner, String taskId) {
        var rows =
                jdbc.query(
                        "SELECT * FROM wechat_task_reminders WHERE owner_id=? AND task_id=?",
                        ROW,
                        owner,
                        taskId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public TaskReminder save(
            String owner, String taskId, String bot, String peer, long at, long expectedVersion) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE)
            throw new IllegalArgumentException("提醒版本无效，请刷新后保存");
        return transaction.execute(
                tx -> {
                    TaskReminder old = find(owner, taskId);
                    if (old != null && old.version() != expectedVersion
                            || old == null && expectedVersion != 0)
                        throw new IllegalStateException("提醒已变化，请刷新后再保存");
                    if (old != null && TaskReminder.SENDING.equals(old.state()))
                        throw new IllegalStateException("提醒正在发送，请稍后操作");
                    Long open =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM farm_tasks WHERE id=? AND user_id=? AND"
                                            + " status NOT IN ('completed','cancelled')",
                                    Long.class,
                                    taskId,
                                    owner);
                    if (open == null || open != 1)
                        throw new IllegalStateException("任务已结束或不存在，无法设置提醒");
                    // An identical repeated save must not rearm an already accepted reminder.
                    if (old != null
                            && old.remindAt() == at
                            && old.botId().equals(bot)
                            && old.peerId().equals(peer)
                            && !List.of(
                                            TaskReminder.CANCELLED,
                                            TaskReminder.FAILED,
                                            TaskReminder.UNKNOWN)
                                    .contains(old.state())) return old;
                    jdbc.update(
                            "INSERT INTO wechat_task_reminders"
                                + " (owner_id,task_id,bot_id,peer_id,remind_at,version,state)"
                                + " VALUES (?,?,?,?,?,?,'PENDING') ON CONFLICT(owner_id,task_id) DO"
                                + " UPDATE SET"
                                + " bot_id=excluded.bot_id,peer_id=excluded.peer_id,remind_at=excluded.remind_at,"
                                + "version=excluded.version,state='PENDING',attempts=0,next_attempt_at=0,batch_id=NULL",
                            owner,
                            taskId,
                            bot,
                            peer,
                            at,
                            expectedVersion + 1);
                    return find(owner, taskId);
                });
    }

    public void cancel(String owner, String taskId, long version) {
        int changed =
                jdbc.update(
                        "UPDATE wechat_task_reminders SET state='CANCELLED',version=version+1 WHERE"
                                + " owner_id=? AND task_id=? AND version=? AND state<>'ACCEPTED'",
                        owner,
                        taskId,
                        version);
        if (changed == 0) {
            var current = find(owner, taskId);
            if (current == null
                    || current.version() != version
                    || !TaskReminder.ACCEPTED.equals(current.state()))
                throw new IllegalStateException("提醒已变化，请刷新后取消");
        }
    }

    public void retry(String owner, String taskId, long version, long now) {
        int changed =
                jdbc.update(
                        "UPDATE wechat_task_reminders SET"
                            + " state='PENDING',version=version+1,attempts=0,next_attempt_at=?,batch_id=NULL"
                            + " WHERE owner_id=? AND task_id=? AND version=? AND state IN"
                            + " ('FAILED','UNKNOWN') AND"
                                + OPEN_TASK,
                        now,
                        owner,
                        taskId,
                        version);
        if (changed != 1) throw new IllegalStateException("提醒状态已变化，不能重试");
    }

    public List<TaskReminder> due(long now, String bot, String peer) {
        return jdbc.query(
                "SELECT * FROM wechat_task_reminders WHERE bot_id=? AND peer_id=? AND state IN"
                        + " ('PENDING','WAITING') AND remind_at<=? AND next_attempt_at<=? AND"
                        + OPEN_TASK
                        + " ORDER BY remind_at,task_id LIMIT 200",
                ROW,
                bot,
                peer,
                now,
                now);
    }

    public void pauseUnavailable(long now, String bot, String peer) {
        jdbc.update(
                "UPDATE wechat_task_reminders SET state='WAITING' WHERE state='PENDING' AND"
                        + " remind_at<=? AND"
                        + OPEN_TASK
                        + " AND (? IS NULL OR bot_id<>? OR peer_id<>?)",
                now,
                bot,
                bot,
                peer);
    }

    public void waiting(TaskReminder r) {
        jdbc.update(
                "UPDATE wechat_task_reminders SET state='WAITING' WHERE owner_id=? AND task_id=?"
                        + " AND version=? AND state IN ('PENDING','WAITING')",
                r.ownerId(),
                r.taskId(),
                r.version());
    }

    public boolean claim(List<TaskReminder> rows, String batch, long now) {
        return Boolean.TRUE.equals(
                transaction.execute(
                        tx -> {
                            for (TaskReminder r : rows) {
                                int count =
                                        jdbc.update(
                                                "UPDATE wechat_task_reminders SET"
                                                    + " state='SENDING',batch_id=? WHERE owner_id=?"
                                                    + " AND task_id=? AND version=? AND state IN"
                                                    + " ('PENDING','WAITING') AND"
                                                        + OPEN_TASK,
                                                batch,
                                                r.ownerId(),
                                                r.taskId(),
                                                r.version());
                                if (count != 1) {
                                    tx.setRollbackOnly();
                                    return false;
                                }
                            }
                            TaskReminder r = rows.getFirst();
                            jdbc.update(
                                    "INSERT INTO wechat_reminder_batches"
                                        + " (id,owner_id,bot_id,peer_id,state,created_at) VALUES"
                                        + " (?,?,?,?,'SENDING',?)",
                                    batch,
                                    r.ownerId(),
                                    r.botId(),
                                    r.peerId(),
                                    now);
                            return true;
                        }));
    }

    public boolean sending(TaskReminder r, String batch) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wechat_task_reminders WHERE owner_id=? AND task_id=?"
                                + " AND version=? AND batch_id=? AND state='SENDING' AND"
                                + OPEN_TASK,
                        Integer.class,
                        r.ownerId(),
                        r.taskId(),
                        r.version(),
                        batch)
                == 1;
    }

    public void outcome(TaskReminder r, String batch, String state, long nextAt) {
        transaction.executeWithoutResult(
                tx -> {
                    int changed =
                            jdbc.update(
                                    "UPDATE wechat_task_reminders SET"
                                        + " state=?,next_attempt_at=?,attempts=attempts+? WHERE"
                                        + " owner_id=? AND task_id=? AND version=? AND batch_id=?"
                                        + " AND state='SENDING'",
                                    state,
                                    nextAt,
                                    TaskReminder.PENDING.equals(state)
                                                    || TaskReminder.FAILED.equals(state)
                                            ? 1
                                            : 0,
                                    r.ownerId(),
                                    r.taskId(),
                                    r.version(),
                                    batch);
                    if (changed == 1 && TaskReminder.ACCEPTED.equals(state))
                        jdbc.update(
                                "UPDATE wechat_reminder_batches SET accepted_count=accepted_count+1"
                                        + " WHERE id=?",
                                batch);
                });
    }

    public void finishBatch(String batch, String state) {
        transaction.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "UPDATE wechat_task_reminders SET state='UNKNOWN' WHERE batch_id=? AND"
                                    + " state='SENDING'",
                            batch);
                    jdbc.update(
                            "UPDATE wechat_reminder_batches SET state=? WHERE id=?", state, batch);
                });
    }

    public void recoverInterrupted() {
        transaction.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "UPDATE wechat_task_reminders SET state='UNKNOWN' WHERE"
                                    + " state='SENDING'");
                    jdbc.update(
                            "UPDATE wechat_reminder_batches SET state='UNKNOWN' WHERE"
                                    + " state='SENDING'");
                });
    }
}
