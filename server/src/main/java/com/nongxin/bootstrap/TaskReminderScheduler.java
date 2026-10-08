package com.nongxin.bootstrap;

import com.nongxin.service.TaskReminderService;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Component
public class TaskReminderScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TaskReminderScheduler.class);
    private final TaskReminderService reminders;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "nongxin-task-reminders");
                        t.setDaemon(true);
                        return t;
                    });
    private boolean started;

    public TaskReminderScheduler(TaskReminderService reminders) {
        this.reminders = reminders;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || executor.isShutdown()) return;
        reminders.recoverInterrupted();
        started = true;
        executor.scheduleWithFixedDelay(
                () -> {
                    try {
                        reminders.dispatchDue();
                    } catch (Exception failure) {
                        LOG.warn("微信提醒调度未完成（{}）", failure.getClass().getSimpleName());
                    }
                },
                0,
                30,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0 && executor.awaitTermination(remaining, TimeUnit.NANOSECONDS))
                        return;
                    throw new IllegalStateException("提醒发送线程尚未停止");
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    executor.shutdownNow();
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
