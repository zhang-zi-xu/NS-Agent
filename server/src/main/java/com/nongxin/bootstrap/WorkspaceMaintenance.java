package com.nongxin.bootstrap;

import com.nongxin.service.TaskService;
import com.nongxin.service.UploadService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup maintenance has no HTTP responsibilities and never treats unknown references as unused.
 */
@Component
public class WorkspaceMaintenance implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMaintenance.class);
    private final TaskService tasks;
    private final UploadService uploads;

    public WorkspaceMaintenance(TaskService tasks, UploadService uploads) {
        this.tasks = tasks;
        this.uploads = uploads;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int merged = tasks.mergeDuplicates();
        if (merged > 0) log.info("[task] 启动自动合并重复任务：清理 {} 条", merged);
        uploads.cleanupUnreferenced();
    }
}
