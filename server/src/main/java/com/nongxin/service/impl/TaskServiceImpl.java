package com.nongxin.service.impl;

import com.nongxin.domain.task.FarmTask;
import com.nongxin.domain.task.TaskRecord;
import com.nongxin.domain.task.TaskStatus;
import com.nongxin.repository.TaskRepository;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.TaskService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农事任务与执行/复查记录。
 *
 * <p>状态推进规则见 {@link TaskStatus}：进入「已执行待复查」必须提交执行记录， 进入「已完成」必须提交复查记录——AI 不能替用户宣告任务完成。 同一方案项（来源消息 +
 * 方案项 ID）重复登记时返回已存在的任务，保证幂等。
 */
@Service
public class TaskServiceImpl implements TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final TaskRepository repository;
    private final CurrentUser currentUser;

    public TaskServiceImpl(TaskRepository repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    // ---- 查询 ----

    public List<FarmTask> list() {
        Map<String, List<TaskRecord>> records = recordsByTask(null);
        return repository.list(currentUser.id()).stream()
                .map(task -> task.withRecords(records.getOrDefault(task.id(), List.of())))
                .toList();
    }

    public FarmTask get(String id) {
        FarmTask task = repository.find(currentUser.id(), id);
        return task == null ? null : task.withRecords(records(id));
    }

    public List<TaskRecord> records(String taskId) {
        return repository.records(currentUser.id(), taskId);
    }

    /** Recent field tasks, with only records belonging to the same owner. */
    public List<FarmTask> forField(String fieldId, int limit) {
        if (fieldId == null || fieldId.isBlank()) return List.of();
        Map<String, List<TaskRecord>> records = recordsByTask(fieldId);
        return repository.forField(currentUser.id(), fieldId, limit).stream()
                .map(task -> task.withRecords(records.getOrDefault(task.id(), List.of())))
                .toList();
    }

    private Map<String, List<TaskRecord>> recordsByTask(String fieldId) {
        Map<String, List<TaskRecord>> grouped = new LinkedHashMap<>();
        for (TaskRecord record : repository.allRecords(currentUser.id(), fieldId)) {
            grouped.computeIfAbsent(record.taskId(), key -> new ArrayList<>()).add(record);
        }
        return grouped;
    }

    // ---- 写入 ----

    /** 登记任务；同一来源消息的同一方案项、或同一田块里同名未完成的任务，都复用已有记录（幂等）。 */
    public FarmTask create(FarmTask task) {
        FarmTask existing = findByPlanItem(task.sourceMessageId(), task.planItemId());
        if (existing != null) {
            log.info(
                    "[task] 方案项已登记，复用任务 {}（source={} item={}）",
                    existing.id(),
                    task.sourceMessageId(),
                    task.planItemId());
            return existing;
        }
        // 跨轮去重：模型每轮都会重新生成方案卡，方案项 ID 只在单条消息内唯一，
        // 所以再按"同一田块 + 同名 + 还没做完"兜一层，避免待办里堆出孪生任务。
        FarmTask duplicate = findOpenByTitle(task.fieldId(), task.title());
        if (duplicate != null) {
            log.info("[task] 同名未完成任务已存在，复用 {}：{}", duplicate.id(), duplicate.title());
            return duplicate;
        }
        String now = now();
        TaskStatus status = TaskStatus.of(task.status());
        repository.create(currentUser.id(), task, status, now);

        return get(task.id());
    }

    /** 同一田块里还没做完的同名任务（忽略空格差异）；用于跨轮去重。 */
    public FarmTask findOpenByTitle(String fieldId, String title) {
        if (title == null || title.isBlank()) return null;
        String wanted = title.replaceAll("[\\s　]+", "");
        if (wanted.isEmpty()) return null;
        for (FarmTask task : list()) {
            TaskStatus status = TaskStatus.of(task.status());
            if (status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED) continue;
            boolean sameField =
                    fieldId == null || fieldId.isBlank()
                            ? task.fieldId() == null || task.fieldId().isBlank()
                            : fieldId.equals(task.fieldId());
            if (!sameField) continue;
            if (task.title().replaceAll("[\\s　]+", "").equals(wanted)) return task;
        }
        return null;
    }

    public FarmTask findByPlanItem(String sourceMessageId, String planItemId) {
        if (sourceMessageId == null
                || sourceMessageId.isBlank()
                || planItemId == null
                || planItemId.isBlank()) return null;
        FarmTask existing =
                repository.findByPlanItem(currentUser.id(), sourceMessageId, planItemId);
        return existing == null ? null : get(existing.id());
    }

    /** 字段编辑；状态变化交给状态机校验（需要记录的推进会被拒绝，请走 addRecord）。 */
    public FarmTask update(String id, FarmTask task) {
        FarmTask current = get(id);
        if (current == null) return null;
        TaskStatus target = TaskStatus.of(task.status());
        if (!target.code().equals(current.status())) return changeStatus(id, target);
        repository.update(currentUser.id(), id, task, now());

        return get(id);
    }

    /** 直接切换状态（确认安排、取消、重新打开、退回重做）。 */
    public FarmTask changeStatus(String id, TaskStatus target) {
        FarmTask current = get(id);
        if (current == null) return null;
        TaskStatus from = TaskStatus.of(current.status());
        if (from == target) return current;
        if (TaskStatus.requiresRecord(target)) {
            throw new IllegalStateException(
                    "「"
                            + target.label()
                            + "」必须由提交"
                            + (target == TaskStatus.AWAITING_REVIEW ? "执行" : "复查")
                            + "记录进入，不能直接改状态");
        }
        if (!from.allows(target)) {
            throw new IllegalStateException(
                    "不能从「" + from.label() + "」直接改为「" + target.label() + "」");
        }
        String now = now();
        String confirmedAt =
                target == TaskStatus.PENDING && from == TaskStatus.PENDING_CONFIRMATION
                        ? now
                        : null;
        repository.changeStatus(currentUser.id(), id, target.code(), now, confirmedAt);

        log.info("[task] {} 状态 {} → {}", id, from.code(), target.code());
        return get(id);
    }

    /** 提交执行/复查记录，并推进状态： 执行记录 → 已执行待复查；复查记录 → 已完成。记录本身永远保留。 */
    public FarmTask addRecord(String taskId, TaskRecord record) {
        FarmTask task = get(taskId);
        if (task == null) return null;
        TaskStatus from = TaskStatus.of(task.status());
        boolean execution = TaskRecord.KIND_EXECUTION.equals(record.kind());
        TaskStatus target = execution ? TaskStatus.AWAITING_REVIEW : TaskStatus.COMPLETED;

        if (from == TaskStatus.CANCELLED) {
            throw new IllegalStateException("任务已取消，如需继续请先恢复为待执行");
        }
        if (!execution && (from == TaskStatus.PENDING_CONFIRMATION || from == TaskStatus.PENDING)) {
            throw new IllegalStateException("还没有执行记录：复查记录需要在执行之后再提交");
        }
        if (from == TaskStatus.COMPLETED && execution) {
            throw new IllegalStateException("任务已完成，如需再执行一次请先把状态改回待执行");
        }
        repository.addRecord(currentUser.id(), taskId, record);

        String now = now();
        if (from != target) {
            repository.advanceAfterRecord(currentUser.id(), taskId, target.code(), now);

            log.info(
                    "[task] {} 状态 {} → {}（{}：{}）",
                    taskId,
                    from.code(),
                    target.code(),
                    TaskRecord.kindLabel(record.kind()),
                    record.date());
        } else {
            repository.touch(currentUser.id(), taskId, now);
        }
        return get(taskId);
    }

    /**
     * 自动合并重复任务：同一田块 + 同名 + 同日期 + 都还没做完 + 没有任何执行/复查记录 → 只保留最早创建的一条。 保守边界（宁可留着让用户自己决定）：
     *
     * <ul>
     *   <li>已完成、已取消的任务不参与合并；
     *   <li>提交过执行或复查记录的任务不自动删除——那是用户写过的事实，删了就丢了；
     *   <li>日期不同的同名任务视为两次安排，不合并。
     * </ul>
     *
     * 每次启动执行一次，用于清理历史遗留的重复（例如模型连着给多张方案卡、用户逐张点加入）。
     */
    public int mergeDuplicates() {
        Map<String, FarmTask> keep = new LinkedHashMap<>();
        List<FarmTask> remove = new ArrayList<>();
        for (FarmTask task : list()) { // list() 按 task_date、created_at、id 排序，最早的在前面
            TaskStatus status = TaskStatus.of(task.status());
            if (status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED) continue;
            if (!task.records().isEmpty()) continue;
            String key =
                    (task.fieldId() == null ? "-" : task.fieldId())
                            + "|"
                            + task.title().replaceAll("[\\s　]+", "")
                            + "|"
                            + task.date();
            FarmTask first = keep.get(key);
            if (first == null) {
                keep.put(key, task);
                continue;
            }
            remove.add(task);
            log.info("[task] 自动合并重复任务：删除 {}「{}」（保留 {}）", task.id(), task.title(), first.id());
        }
        for (FarmTask task : remove) delete(task.id());
        return remove.size();
    }

    public boolean delete(String id) {
        // 别人的任务一律不动
        if (get(id) == null) return false;
        return repository.delete(currentUser.id(), id);
    }

    // ---- 辅助 ----

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }
}
