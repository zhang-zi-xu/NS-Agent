package com.nongxin.service;

import com.nongxin.dto.reminder.ReminderDtos;

public interface TaskReminderService {
    ReminderDtos.Listing list();

    ReminderDtos.View save(String taskId, ReminderDtos.Save request);

    void cancel(String taskId, long version);

    void retry(String taskId, ReminderDtos.Retry request);

    ReminderDtos.Suggestion suggest(ReminderDtos.Suggest request);

    void dispatchDue();

    void recoverInterrupted();
}
