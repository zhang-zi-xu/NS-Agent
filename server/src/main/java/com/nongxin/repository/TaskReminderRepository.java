package com.nongxin.repository;

import com.nongxin.domain.reminder.TaskReminder;

import java.util.List;

public interface TaskReminderRepository {
    List<TaskReminder> list(String owner);

    TaskReminder find(String owner, String taskId);

    TaskReminder save(
            String owner, String taskId, String bot, String peer, long at, long expectedVersion);

    void cancel(String owner, String taskId, long version);

    void retry(String owner, String taskId, long version, long now);

    List<TaskReminder> due(long now, String botId, String peerId);

    void pauseUnavailable(long now, String botId, String peerId);

    void waiting(TaskReminder reminder);

    boolean claim(List<TaskReminder> reminders, String batchId, long now);

    boolean sending(TaskReminder reminder, String batchId);

    void outcome(TaskReminder reminder, String batchId, String state, long nextAttemptAt);

    void finishBatch(String batchId, String state);

    void recoverInterrupted();
}
