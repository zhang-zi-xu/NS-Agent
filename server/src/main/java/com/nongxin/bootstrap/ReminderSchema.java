package com.nongxin.bootstrap;

import org.springframework.jdbc.core.JdbcTemplate;

/** v7 migration; cancellation constraints also cover task deletion/maintenance outside the UI. */
final class ReminderSchema {
    private ReminderSchema() {}

    static void install(JdbcTemplate jdbc) {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS wechat_task_reminders (owner_id TEXT NOT NULL, task_id"
                    + " TEXT NOT NULL, bot_id TEXT NOT NULL, peer_id TEXT NOT NULL,remind_at"
                    + " INTEGER NOT NULL, version INTEGER NOT NULL, state TEXT NOT NULL,attempts"
                    + " INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT"
                    + " 0,batch_id TEXT, PRIMARY KEY(owner_id, task_id))");
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_reminders_due ON wechat_task_reminders(state,"
                    + " remind_at, next_attempt_at)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS wechat_reminder_batches (id TEXT PRIMARY KEY,owner_id"
                    + " TEXT NOT NULL, bot_id TEXT NOT NULL, peer_id TEXT NOT NULL,state TEXT NOT"
                    + " NULL, created_at INTEGER NOT NULL, accepted_count INTEGER NOT NULL DEFAULT"
                    + " 0)");
        constraints(jdbc);
    }

    static void constraints(JdbcTemplate jdbc) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND"
                            + " name='farm_tasks'",
                        Integer.class)
                == 0) return;
        // SQLite statements are executed individually: SQL script splitters do not understand
        // trigger bodies.
        jdbc.execute(
                "CREATE TRIGGER IF NOT EXISTS cancel_task_reminder_on_close AFTER UPDATE OF status"
                    + " ON farm_tasks WHEN NEW.status IN ('completed','cancelled') BEGIN UPDATE"
                    + " wechat_task_reminders SET state='CANCELLED' WHERE task_id=NEW.id AND"
                    + " owner_id=NEW.user_id AND state NOT IN ('ACCEPTED','CANCELLED'); END");
        jdbc.execute(
                "CREATE TRIGGER IF NOT EXISTS cancel_task_reminder_on_delete BEFORE DELETE ON"
                    + " farm_tasks BEGIN UPDATE wechat_task_reminders SET state='CANCELLED' WHERE"
                    + " task_id=OLD.id AND owner_id=OLD.user_id AND state NOT IN"
                    + " ('ACCEPTED','CANCELLED'); END");
    }
}
