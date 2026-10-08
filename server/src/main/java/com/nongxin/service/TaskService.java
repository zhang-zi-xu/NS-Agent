package com.nongxin.service;

import com.nongxin.domain.task.FarmTask;
import com.nongxin.domain.task.TaskRecord;
import com.nongxin.domain.task.TaskStatus;

import java.util.List;
import java.util.UUID;

/** Task registration, guarded state transitions and factual execution/review records. */
public interface TaskService {
    List<FarmTask> list();

    FarmTask get(String id);

    List<TaskRecord> records(String taskId);

    List<FarmTask> forField(String fieldId, int limit);

    FarmTask create(FarmTask task);

    FarmTask findOpenByTitle(String fieldId, String title);

    FarmTask findByPlanItem(String sourceMessageId, String planItemId);

    FarmTask update(String id, FarmTask task);

    FarmTask changeStatus(String id, TaskStatus target);

    FarmTask addRecord(String taskId, TaskRecord record);

    int mergeDuplicates();

    boolean delete(String id);

    static String newId() {
        return "t-" + UUID.randomUUID();
    }
}
