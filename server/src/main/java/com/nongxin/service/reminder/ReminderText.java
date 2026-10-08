package com.nongxin.service.reminder;

import com.nongxin.domain.reminder.TaskReminder;
import com.nongxin.domain.task.FarmTask;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ReminderText {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(ReminderTimeAdvisor.ZONE);

    private ReminderText() {}

    public record Item(TaskReminder reminder, FarmTask task) {}

    /** At most three items per message; bounded text stays below the bridge's message size. */
    public static String format(List<Item> items, boolean delayed) {
        StringBuilder text = new StringBuilder(delayed ? "农心 · 错过提醒汇总\n" : "农心 · 农事任务提醒\n");
        for (Item item : items) {
            FarmTask task = item.task();
            text.append("\n• ").append(shortText(task.title(), 120));
            if (task.fieldName() != null && !task.fieldName().isBlank())
                text.append("\n田块：").append(shortText(task.fieldName(), 60));
            text.append("\n计划提醒：")
                    .append(TIME.format(Instant.ofEpochMilli(item.reminder().remindAt())))
                    .append("（北京时间）");
            String action = "awaiting_review".equals(task.status()) ? task.review() : task.method();
            if (action != null && !action.isBlank())
                text.append("\n事项：").append(shortText(action, 150));
            text.append('\n');
        }
        return text.append("\n请按实际情况处理，并在网页记录执行或复查结果。此消息不表示任务已执行。电脑和服务保持运行才能继续提醒。").toString();
    }

    private static String shortText(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
