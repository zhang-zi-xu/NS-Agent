package com.nongxin.repository;

import com.nongxin.domain.task.FarmTask;
import com.nongxin.domain.task.TaskRecord;
import com.nongxin.domain.task.TaskStatus;

import java.util.List;

public interface TaskRepository {
    List<FarmTask> list(String userId);

    FarmTask find(String userId, String id);

    List<TaskRecord> records(String userId, String taskId);

    List<FarmTask> forField(String userId, String fieldId, int limit);

    List<TaskRecord> allRecords(String userId, String fieldId);

    void create(String userId, FarmTask task, TaskStatus status, String now);

    FarmTask findByPlanItem(String userId, String sourceMessageId, String planItemId);

    void update(String userId, String id, FarmTask task, String updatedAt);

    void changeStatus(String userId, String id, String status, String now, String confirmedAt);

    void addRecord(String userId, String taskId, TaskRecord record);

    void advanceAfterRecord(String userId, String taskId, String target, String now);

    void touch(String userId, String taskId, String now);

    boolean delete(String userId, String id);
}
