package com.nongxin.controller;

import com.nongxin.dto.reminder.ReminderDtos;
import com.nongxin.security.CurrentUser;
import com.nongxin.security.LocalWechatAccess;
import com.nongxin.service.TaskReminderService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/wechat/reminders")
public class TaskReminderController {
    private final TaskReminderService reminders;
    private final CurrentUser user;

    public TaskReminderController(TaskReminderService reminders, CurrentUser user) {
        this.reminders = reminders;
        this.user = user;
    }

    private ResponseEntity<?> local(HttpServletRequest request, Supplier<?> work) {
        if (!LocalWechatAccess.allowed(request))
            return ResponseEntity.status(403)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "微信提醒仅可从本机页面操作"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(user.withSnapshot(user.capture(), work::get));
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        return local(request, reminders::list);
    }

    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(
            HttpServletRequest request, @RequestBody ReminderDtos.Suggest body) {
        return local(request, () -> reminders.suggest(body));
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<?> save(
            HttpServletRequest request,
            @PathVariable String taskId,
            @RequestBody ReminderDtos.Save body) {
        return local(request, () -> reminders.save(taskId, body));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> cancel(
            HttpServletRequest request, @PathVariable String taskId, @RequestParam long version) {
        return local(
                request,
                () -> {
                    reminders.cancel(taskId, version);
                    return Map.of("cancelled", true);
                });
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<?> retry(
            HttpServletRequest request,
            @PathVariable String taskId,
            @RequestBody ReminderDtos.Retry body) {
        return local(
                request,
                () -> {
                    reminders.retry(taskId, body);
                    return Map.of("queued", true);
                });
    }
}
