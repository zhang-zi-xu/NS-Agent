package com.nongxin.service.impl;

import com.nongxin.domain.reminder.TaskReminder;
import com.nongxin.domain.task.FarmTask;
import com.nongxin.dto.reminder.ReminderDtos;
import com.nongxin.repository.TaskReminderRepository;
import com.nongxin.repository.TaskRepository;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.TaskReminderService;
import com.nongxin.service.WechatBridgeService;
import com.nongxin.service.reminder.ReminderText;
import com.nongxin.service.reminder.ReminderTimeAdvisor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class TaskReminderServiceImpl implements TaskReminderService {
    private final TaskReminderRepository reminders;
    private final TaskRepository tasks;
    private final WechatBridgeService bridge;
    private final CurrentUser user;
    private final ReminderTimeAdvisor advisor;
    private final Clock clock;

    @Autowired
    public TaskReminderServiceImpl(
            TaskReminderRepository reminders,
            TaskRepository tasks,
            WechatBridgeService bridge,
            CurrentUser user,
            ReminderTimeAdvisor advisor) {
        this(reminders, tasks, bridge, user, advisor, Clock.systemUTC());
    }

    public TaskReminderServiceImpl(
            TaskReminderRepository reminders,
            TaskRepository tasks,
            WechatBridgeService bridge,
            CurrentUser user,
            ReminderTimeAdvisor advisor,
            Clock clock) {
        this.reminders = reminders;
        this.tasks = tasks;
        this.bridge = bridge;
        this.user = user;
        this.advisor = advisor;
        this.clock = clock;
    }

    public ReminderDtos.Listing list() {
        var recipient = bridge.recipient();
        return new ReminderDtos.Listing(
                new ReminderDtos.Connection(
                        recipient != null,
                        recipient != null && recipient.contextReady(),
                        recipient == null ? null : recipient.bindingId()),
                reminders.list(user.id()).stream().map(r -> view(r, recipient)).toList());
    }

    public ReminderDtos.View save(String taskId, ReminderDtos.Save request) {
        String owner = user.id();
        requireOpen(owner, taskId);
        if (request == null || !request.consent())
            throw new IllegalArgumentException("请确认将该任务摘要发送给当前扫码微信");
        var recipient = bridge.recipient();
        if (recipient == null || !recipient.contextReady())
            throw new IllegalStateException("请先连接微信，并向农心发一条消息后再启用提醒");
        if (!recipient.bindingId().equals(request.bindingId()))
            throw new IllegalStateException("微信连接已变化，请重新确认接收账号");
        Instant at;
        try {
            at = OffsetDateTime.parse(request.remindAt()).toInstant();
        } catch (RuntimeException badTime) {
            throw new IllegalArgumentException("请选择包含时区的有效提醒时间");
        }
        if (!at.isAfter(clock.instant())) throw new IllegalArgumentException("提醒时间必须在未来");
        return view(
                reminders.save(
                        owner,
                        taskId,
                        recipient.botId(),
                        recipient.peerId(),
                        at.toEpochMilli(),
                        request.version()),
                recipient);
    }

    public void cancel(String taskId, long version) {
        String owner = user.id();
        var reminder = requireReminder(owner, taskId);
        if (reminder.version() != version) throw new IllegalStateException("提醒已变化，请刷新后操作");
        reminders.cancel(owner, taskId, version);
    }

    public void retry(String taskId, ReminderDtos.Retry request) {
        String owner = user.id();
        requireOpen(owner, taskId);
        var reminder = requireReminder(owner, taskId);
        if (request == null || !request.acknowledgeDuplicate())
            throw new IllegalArgumentException("请确认重试可能产生重复提醒");
        var recipient = bridge.recipient();
        if (!matches(reminder, recipient) || !recipient.contextReady())
            throw new IllegalStateException("请用原接收账号连接微信并发送一条消息");
        reminders.retry(owner, taskId, request.version(), clock.millis());
    }

    public ReminderDtos.Suggestion suggest(ReminderDtos.Suggest request) {
        if (request == null) throw new IllegalArgumentException("请指定已有任务");
        String owner = user.id();
        FarmTask task = requireOpen(owner, request.taskId());
        return advisor.suggest(
                task.withRecords(tasks.records(owner, task.id())),
                request.settings(),
                clock.instant());
    }

    private FarmTask requireOpen(String owner, String taskId) {
        FarmTask task = tasks.find(owner, taskId);
        if (task == null) throw new NoSuchElementException("任务不存在");
        if (!open(task)) throw new IllegalStateException("任务已结束，不能设置提醒");
        return task;
    }

    private TaskReminder requireReminder(String owner, String taskId) {
        TaskReminder result = reminders.find(owner, taskId);
        if (result == null) throw new NoSuchElementException("提醒不存在");
        return result;
    }

    private static boolean open(FarmTask task) {
        return task != null && !List.of("completed", "cancelled").contains(task.status());
    }

    private static boolean matches(TaskReminder r, WechatBridgeService.Recipient recipient) {
        return recipient != null
                && r.botId().equals(recipient.botId())
                && r.peerId().equals(recipient.peerId());
    }

    private static ReminderDtos.View view(TaskReminder r, WechatBridgeService.Recipient recipient) {
        return new ReminderDtos.View(
                r.taskId(),
                Instant.ofEpochMilli(r.remindAt()).toString(),
                r.version(),
                r.state(),
                r.attempts(),
                matches(r, recipient));
    }

    public synchronized void recoverInterrupted() {
        reminders.recoverInterrupted();
    }

    /** Database owner IDs were assigned by the trusted request entrypoint, never by task input. */
    public synchronized void dispatchDue() {
        if (Thread.currentThread().isInterrupted()) return;
        var recipient = bridge.recipient();
        boolean ready = recipient != null && recipient.contextReady();
        reminders.pauseUnavailable(
                clock.millis(),
                ready ? recipient.botId() : null,
                ready ? recipient.peerId() : null);
        if (!ready) return;
        Map<String, List<TaskReminder>> groups = new LinkedHashMap<>();
        for (TaskReminder r :
                reminders.due(clock.millis(), recipient.botId(), recipient.peerId())) {
            if (!matches(r, recipient) || !recipient.contextReady()) {
                reminders.waiting(r);
                continue;
            }
            groups.computeIfAbsent(r.ownerId(), key -> new ArrayList<>()).add(r);
        }
        for (var group : groups.values()) dispatch(group, recipient);
    }

    private void dispatch(List<TaskReminder> group, WechatBridgeService.Recipient recipient) {
        String batch = UUID.randomUUID().toString();
        long now = clock.millis();
        if (!reminders.claim(group, batch, now)) return;
        int accepted = 0;
        String batchState = TaskReminder.UNKNOWN;
        boolean failed = false;
        try {
            for (int start = 0; start < group.size(); start += 3) {
                var part = group.subList(start, Math.min(start + 3, group.size()));
                var items = new ArrayList<ReminderText.Item>();
                WechatBridgeService.Delivery delivery =
                        bridge.notify(
                                recipient,
                                () -> {
                                    // The shared send lock may have queued behind a chat reply.
                                    // Re-read after that wait.
                                    for (TaskReminder r : part) {
                                        FarmTask task = tasks.find(r.ownerId(), r.taskId());
                                        if (open(task) && reminders.sending(r, batch))
                                            items.add(new ReminderText.Item(r, task));
                                    }
                                    if (items.isEmpty()) return null;
                                    boolean delayed =
                                            items.stream()
                                                    .anyMatch(
                                                            i ->
                                                                    TaskReminder.WAITING.equals(
                                                                                    i.reminder()
                                                                                            .state())
                                                                            || clock.millis()
                                                                                            - i.reminder()
                                                                                                    .remindAt()
                                                                                    > 60_000);
                                    return ReminderText.format(items, delayed);
                                });
                if (delivery == WechatBridgeService.Delivery.SKIPPED) continue;
                // A changed connection can reject the channel before invoking the text supplier.
                if (items.isEmpty())
                    for (var r : part) {
                        FarmTask task = tasks.find(r.ownerId(), r.taskId());
                        if (open(task) && reminders.sending(r, batch))
                            items.add(new ReminderText.Item(r, task));
                    }
                if (delivery == WechatBridgeService.Delivery.ACCEPTED) {
                    for (var item : items)
                        reminders.outcome(item.reminder(), batch, TaskReminder.ACCEPTED, 0);
                    accepted += items.size();
                } else {
                    // Only this attempted part can be uncertain. Later parts have never been
                    // submitted.
                    for (var item : items) {
                        var r = item.reminder();
                        String state =
                                switch (delivery) {
                                    case WAITING -> TaskReminder.WAITING;
                                    case REJECTED ->
                                            r.attempts() >= 2
                                                    ? TaskReminder.FAILED
                                                    : TaskReminder.PENDING;
                                    default -> TaskReminder.UNKNOWN;
                                };
                        reminders.outcome(r, batch, state, now + 60_000L * (r.attempts() + 1));
                    }
                    for (int later = start + 3; later < group.size(); later++)
                        reminders.outcome(group.get(later), batch, TaskReminder.WAITING, 0);
                    batchState =
                            delivery == WechatBridgeService.Delivery.UNKNOWN
                                    ? TaskReminder.UNKNOWN
                                    : TaskReminder.FAILED;
                    failed = true;
                    break;
                }
            }
            if (!failed)
                batchState = accepted == 0 ? TaskReminder.CANCELLED : TaskReminder.ACCEPTED;
        } finally {
            reminders.finishBatch(batch, batchState);
        }
    }
}
