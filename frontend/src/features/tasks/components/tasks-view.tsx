import { useMemo, useState, type FormEvent } from 'react';
import {
  BellRing,
  CalendarDays,
  CheckCheck,
  ClipboardList,
  FileText,
  MessageSquare,
  Pencil,
  Plus,
  Route,
  Search,
  Sprout,
  Trash2,
} from 'lucide-react';
import type { FarmTask } from '@/features/tasks/types';
import type { AiSettings } from '@/features/settings/types';
import { TaskReminderPanel } from '@/features/wechat/reminders/task-reminder-panel';
import { TaskScheduleDialog } from './task-schedule-dialog';
import { OPEN_TASK_STATUSES, taskStatusLabel, taskStatusTone } from '../status';
import {
  REVIEW_OUTCOMES,
  buildReviewFollowUpQuestion,
  buildSeasonPlanReport,
  downloadMarkdownFile,
  outcomeText,
  reportFilename,
} from '../review';
import type { FieldProfile } from '@/features/fields/types';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/dialog';
import { NxSelect } from '@/shared/ui/nx-select';
import { EmptyState } from '@/shared/ui/empty-state';
import { ConfirmDelete } from '@/shared/ui/confirm-delete';
import { errorText as errorMessage } from '@/shared/api/client';
import { localDate } from '@/shared/lib/date';
const newId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`;
const formatDate = (date: string) => (date ? date.replaceAll('-', '.') : '日期待定');
export type TasksViewProps = {
  settings?: AiSettings;
  onOpenWechat?: () => void;
  tasks: FarmTask[];
  fields: FieldProfile[];
  onSave: (value: FarmTask) => Promise<unknown>;
  onDelete: (value: string) => Promise<unknown>;
  /** 状态流转：确认安排、取消、退回、重新打开（需要记录的两步由 onRecord 推进） */
  onStatus: (id: string, status: string) => Promise<unknown>;
  onRecord: (
    id: string,
    record: { kind: string; date: string; note: string; outcome?: string },
  ) => Promise<unknown>;
  onDiscuss: (task: FarmTask) => void;
  /** 「据本次复查排下一步」：把结构化追问放进对话草稿，方案卡仍由用户自己点「加入任务」 */
  onReviewFollowUp: (task: FarmTask, question: string) => void;
};

type TaskFilter = 'todo' | 'pending_confirmation' | 'awaiting_review' | 'completed' | 'all';
const TASK_FILTERS: Array<{ id: TaskFilter; label: string }> = [
  { id: 'todo', label: '进行中' },
  { id: 'pending_confirmation', label: '待确认' },
  { id: 'awaiting_review', label: '待复查' },
  { id: 'completed', label: '已完成' },
  { id: 'all', label: '全部' },
];
const recordKindLabel = (kind: string) => (kind === 'review' ? '复查记录' : '执行记录');

export function TasksView({
  settings,
  onOpenWechat,
  tasks,
  fields,
  onSave,
  onDelete,
  onStatus,
  onRecord,
  onDiscuss,
  onReviewFollowUp,
}: TasksViewProps) {
  const [filter, setFilter] = useState<TaskFilter>('todo');
  const [fieldFilter, setFieldFilter] = useState('');
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState<FarmTask | null>(null);
  const [scheduleTarget, setScheduleTarget] = useState<FarmTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<FarmTask | null>(null);
  const [recordTarget, setRecordTarget] = useState<{
    task: FarmTask;
    kind: 'execution' | 'review';
  } | null>(null);
  const [recordDraft, setRecordDraft] = useState({ date: '', note: '', outcome: '' });
  const [expanded, setExpanded] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [actingIds, setActingIds] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [listError, setListError] = useState('');
  const [status, setStatus] = useState('');
  const [reportHint, setReportHint] = useState('');
  const today = localDate();
  const open = tasks.filter((task) => OPEN_TASK_STATUSES.includes(task.status));
  const toReview = tasks.filter((task) => task.status === 'awaiting_review');
  const waiting = open.filter((task) => task.status !== 'awaiting_review');
  const overdue = waiting.filter((task) => !!task.date && task.date < today);
  const dueToday = waiting.filter((task) => task.date === today);
  const filtered = useMemo(
    () =>
      tasks
        .filter((task) => {
          if (fieldFilter && task.fieldId !== fieldFilter) return false;
          if (filter === 'todo' && !OPEN_TASK_STATUSES.includes(task.status)) return false;
          if (filter === 'pending_confirmation' && task.status !== 'pending_confirmation')
            return false;
          if (filter === 'awaiting_review' && task.status !== 'awaiting_review') return false;
          if (filter === 'completed' && task.status !== 'completed') return false;
          return `${task.title} ${task.note ?? ''} ${task.fieldName ?? ''}`
            .toLowerCase()
            .includes(query.trim().toLowerCase());
        })
        .sort(
          (a, b) =>
            Number(!OPEN_TASK_STATUSES.includes(a.status)) -
              Number(!OPEN_TASK_STATUSES.includes(b.status)) ||
            a.date.localeCompare(b.date) ||
            b.createdAt.localeCompare(a.createdAt),
        ),
    [tasks, filter, fieldFilter, query],
  );
  /** 先按田块分组，组内按时间（逾期→今天→以后→待定）；组之间按最早日期排，顺序即执行顺序 */
  const taskGroups = useMemo(() => {
    const grouped = new Map<
      string,
      { key: string; label: string; hint: string; items: FarmTask[]; dueText: string }
    >();
    for (const task of filtered) {
      const key = task.fieldId ?? '__none__';
      let group = grouped.get(key);
      if (!group) {
        const field = fields.find((item) => item.id === task.fieldId);
        group = {
          key,
          label: field?.name || task.fieldName || '未关联田块',
          hint: field?.crop || (task.fieldId ? '' : '通用咨询'),
          items: [],
          dueText: '',
        };
        grouped.set(key, group);
      }
      group.items.push(task);
    }
    const groups = [...grouped.values()];
    for (const group of groups) {
      const dated = group.items
        .filter((task) => !!task.date)
        .sort((a, b) => a.date.localeCompare(b.date));
      group.dueText = dated.length ? formatDate(dated[0].date) : '';
      // 组内：待确认的排最前（它挡着后面的事），其余按日期升序、同日期按创建时间
      group.items.sort(
        (a, b) =>
          Number(b.status === 'pending_confirmation') -
            Number(a.status === 'pending_confirmation') ||
          (a.date || '9999-12-31').localeCompare(b.date || '9999-12-31') ||
          a.createdAt.localeCompare(b.createdAt),
      );
    }
    return groups.sort((a, b) =>
      (a.items[0]?.date || '9999-12-31').localeCompare(b.items[0]?.date || '9999-12-31'),
    );
  }, [filtered, fields]);
  const titles = (list: FarmTask[]) =>
    `${list
      .slice(0, 3)
      .map((task) => task.title)
      .join('、')}${list.length > 3 ? '…' : ''}`;
  const edit = (task?: FarmTask) => {
    setError('');
    setEditor(
      task
        ? { ...task }
        : {
            id: newId('t'),
            title: '',
            date: today,
            status: 'pending',
            createdAt: new Date().toISOString(),
            fieldId: '',
            fieldName: '',
            condition: '',
            method: '',
            review: '',
            note: '',
            timeWindow: '',
            materials: '',
            risk: '',
          },
    );
  };
  const update = <K extends keyof FarmTask>(key: K, value: FarmTask[K]) =>
    setEditor((current) => current && { ...current, [key]: value });
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editor || busy) return;
    if (!editor.title.trim()) {
      setError('请填写任务名称。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const field = fields.find((item) => item.id === editor.fieldId);
      const prepared = {
        ...editor,
        title: editor.title.trim(),
        fieldId: field?.id ?? null,
        fieldName: field?.name ?? null,
        condition: editor.condition?.trim() || '',
        method: editor.method?.trim() || '',
        review: editor.review?.trim() || '',
        note: editor.note?.trim() || '',
        timeWindow: editor.timeWindow?.trim() || '',
        materials: editor.materials?.trim() || '',
        risk: editor.risk?.trim() || '',
      };
      await onSave(prepared);
      setEditor(null);
      setStatus('任务已保存。');
      if (!tasks.some((task) => task.id === prepared.id)) setScheduleTarget(prepared);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  const act = async (task: FarmTask, next: string, message: string) => {
    if (actingIds.includes(task.id)) return;
    setActingIds((ids) => [...ids, task.id]);
    setListError('');
    try {
      await onStatus(task.id, next);
      setStatus(message);
    } catch (cause) {
      setListError(errorMessage(cause));
    } finally {
      setActingIds((ids) => ids.filter((id) => id !== task.id));
    }
  };
  const openRecord = (task: FarmTask, kind: 'execution' | 'review') => {
    setError('');
    setRecordDraft({ date: today, note: '', outcome: '' });
    setRecordTarget({ task, kind });
  };
  const submitRecord = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!recordTarget || busy) return;
    if (!recordDraft.note.trim()) {
      setError(recordTarget.kind === 'review' ? '请写下复查看到的情况。' : '请写下实际做了什么。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await onRecord(recordTarget.task.id, {
        kind: recordTarget.kind,
        date: recordDraft.date || today,
        note: recordDraft.note.trim(),
        outcome: recordTarget.kind === 'review' ? recordDraft.outcome : '',
      });
      setStatus(
        recordTarget.kind === 'review'
          ? '复查记录已提交，任务标记为已完成。'
          : '执行记录已提交，任务进入待复查。',
      );
      setRecordTarget(null);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  const toggleRecords = (id: string) =>
    setExpanded((ids) => (ids.includes(id) ? ids.filter((item) => item !== id) : [...ids, id]));
  /** 据本次复查排下一步：组装结构化追问 → 回到对话 → 助手给方案卡 → 用户在卡片上自己点「加入任务」 */
  const followUp = (task: FarmTask) => {
    setReportHint('');
    setListError('');
    const field = fields.find((item) => item.id === task.fieldId) ?? null;
    onReviewFollowUp(task, buildReviewFollowUpQuestion(task, field, today));
  };
  /** 导出当前所选田块的《本季农事规划报告》：只汇总真实数据，缺字段一律写「未记录」。 */
  const exportReport = () => {
    const field = fields.find((item) => item.id === fieldFilter);
    if (!field) {
      setReportHint(
        fields.length
          ? '请先在上方按田块筛选里选择要导出的田块，再导出本季农事规划报告。'
          : '还没有田块档案，先在「我的田块」里建一块地，再导出本季农事规划报告。',
      );
      return;
    }
    setReportHint('');
    setListError('');
    downloadMarkdownFile(
      reportFilename(field.name),
      buildSeasonPlanReport(
        field,
        tasks.filter((task) => task.fieldId === field.id),
      ),
    );
    setStatus(`已导出「${field.name}」的本季农事规划报告。`);
  };

  return (
    <section className="nx-view nx-tasks-view" aria-labelledby="nx-tasks-title">
      <header className="nx-view-head">
        <div>
          <p className="nx-eyebrow">FARM ROUTINE / 农事安排</p>
          <h1 id="nx-tasks-title">把想做的事，落到每一天。</h1>
          <p className="nx-muted">方案确认后成为任务；执行完提交记录，复查后再决定下一步。</p>
        </div>
        <button className="nx-button is-primary" onClick={() => edit()}>
          <Plus size={18} />
          新建任务
        </button>
      </header>
      <div className="nx-task-report">
        <button className="nx-button" onClick={exportReport}>
          <FileText size={17} />
          导出本季农事规划报告
        </button>
        <span className="nx-muted">
          {fieldFilter
            ? `导出「${fields.find((item) => item.id === fieldFilter)?.name ?? ''}」的档案、任务与执行/复查记录（Markdown）`
            : '按当前所选田块生成：档案、计划总览、执行与复查记录、资料依据、风险与注意'}
        </span>
      </div>
      {reportHint && (
        <p className="nx-status is-hint" role="status">
          {reportHint}
        </p>
      )}
      <div className="nx-view-summary">
        <span>
          <strong>{open.length}</strong> 项进行中
        </span>
        <span>
          <strong>{toReview.length}</strong> 项待复查
        </span>
        <span>
          <strong>{overdue.length}</strong> 项已逾期
        </span>
        <span className="nx-muted">完成状态只由你提交的记录推进</span>
      </div>
      {(overdue.length > 0 || dueToday.length > 0 || toReview.length > 0) && (
        <div className="nx-reminders" role="status" aria-label="到期与待复查提醒">
          <b>
            <BellRing size={16} />
            今天要留意
          </b>
          <ul>
            {overdue.length > 0 && (
              <li>
                <button type="button" onClick={() => setFilter('todo')}>
                  已逾期 {overdue.length} 项：{titles(overdue)}
                </button>
              </li>
            )}
            {dueToday.length > 0 && (
              <li>
                <button type="button" onClick={() => setFilter('todo')}>
                  今天到期 {dueToday.length} 项：{titles(dueToday)}
                </button>
              </li>
            )}
            {toReview.length > 0 && (
              <li>
                <button type="button" onClick={() => setFilter('awaiting_review')}>
                  执行完等复查 {toReview.length} 项：{titles(toReview)}
                </button>
              </li>
            )}
          </ul>
          <p className="nx-fine nx-muted">这里只在页面内提醒；微信通知须在任务上另行设置并保存。</p>
        </div>
      )}
      <p className="nx-status" role="status">
        {status}
      </p>
      {listError && (
        <p className="nx-error" role="alert">
          {listError}
        </p>
      )}
      {tasks.length === 0 ? (
        <EmptyState
          icon={<ClipboardList size={30} />}
          title="给下一件农事留个位置"
          action={
            <button className="nx-button is-primary" onClick={() => edit()}>
              <Plus size={17} />
              记下第一件事
            </button>
          }
        >
          巡田、灌溉、复查，都可以从一条简单的计划开始；也可以让农心给出方案后点「加入任务」。
        </EmptyState>
      ) : (
        <>
          <div className="nx-toolbar nx-task-toolbar">
            <div className="nx-filter-tabs" aria-label="按任务状态筛选">
              {TASK_FILTERS.map((item) => (
                <button
                  key={item.id}
                  className={filter === item.id ? 'is-active' : ''}
                  aria-pressed={filter === item.id}
                  onClick={() => setFilter(item.id)}
                >
                  {item.label}
                </button>
              ))}
            </div>
            <NxSelect
              ariaLabel="按田块筛选任务"
              className="nx-filter-trigger"
              value={fieldFilter}
              options={[
                { value: '', label: '全部田块' },
                ...fields.map((field) => ({
                  value: field.id,
                  label: field.name,
                  hint: field.crop,
                })),
              ]}
              onValueChange={setFieldFilter}
            />
          </div>
          <label className="nx-search nx-task-search">
            <Search size={18} aria-hidden="true" />
            <input
              aria-label="搜索任务"
              placeholder="搜索任务名称或备注"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          {filtered.length === 0 ? (
            <EmptyState
              icon={filter === 'completed' ? <CheckCheck size={28} /> : <ClipboardList size={28} />}
              title={
                filter === 'todo' && !query && !fieldFilter
                  ? '当前没有进行中的任务'
                  : '这里还没有任务'
              }
              action={
                <button
                  className="nx-button"
                  onClick={() => {
                    setFilter('all');
                    setFieldFilter('');
                    setQuery('');
                  }}
                >
                  查看全部任务
                </button>
              }
            >
              可以调整筛选条件，或新建一项农事安排。
            </EmptyState>
          ) : (
            <div className="nx-task-list">
              {/* 先按田块分组，组内按时间（逾期→今天→以后→待定），顺序就是执行顺序 */}
              {taskGroups.map((group) => (
                <section className="nx-task-group" key={group.key}>
                  <header className="nx-task-group-head">
                    <div>
                      <b>{group.label}</b>
                      {group.hint && <span>{group.hint}</span>}
                    </div>
                    <span className="nx-muted">
                      {group.items.length} 项{group.dueText ? ` · 最早 ${group.dueText}` : ''}
                    </span>
                  </header>
                  {group.items.map((task, index) => {
                    const pastDue =
                      OPEN_TASK_STATUSES.includes(task.status) &&
                      task.status !== 'awaiting_review' &&
                      !!task.date &&
                      task.date < today;
                    const field = fields.find((item) => item.id === task.fieldId);
                    const records = task.records ?? [];
                    const cards = task.evidenceCards ?? [];
                    const evidenceIds = task.evidence ?? [];
                    const acting = actingIds.includes(task.id);
                    const isOpen = expanded.includes(task.id);
                    return (
                      <article key={task.id} className={`nx-task-row is-${task.status}`}>
                        <div className="nx-task-state">
                          <span className="nx-task-order">{index + 1}</span>
                          <span className={`nx-badge ${taskStatusTone(task)}`}>
                            {taskStatusLabel(task)}
                          </span>
                        </div>
                        <div className="nx-task-main">
                          <button className="nx-task-title" onClick={() => edit(task)}>
                            {task.title}
                          </button>
                          <div className="nx-task-meta">
                            <span className={pastDue ? 'is-overdue' : ''}>
                              <CalendarDays size={14} />
                              {formatDate(task.date)}
                              {pastDue
                                ? ' · 已逾期'
                                : task.date === today && OPEN_TASK_STATUSES.includes(task.status)
                                  ? ' · 今天'
                                  : ''}
                            </span>
                            {field?.name ? (
                              <span>
                                <Sprout size={14} />
                                {field.name}
                              </span>
                            ) : task.fieldName ? (
                              <span>
                                <Sprout size={14} />
                                {task.fieldName}（原关联田块）
                              </span>
                            ) : (
                              <span>未关联田块</span>
                            )}
                            {task.sourceMessageId && (
                              <span>
                                <MessageSquare size={14} />
                                来自对话建议
                              </span>
                            )}
                            {task.completedAt && (
                              <span>完成于 {formatDate(task.completedAt.slice(0, 10))}</span>
                            )}
                          </div>
                          {task.timeWindow && (
                            <p className="nx-task-condition">时间窗口：{task.timeWindow}</p>
                          )}
                          {task.condition && (
                            <p className="nx-task-condition">执行条件：{task.condition}</p>
                          )}
                          {task.method && <p className="nx-task-note">操作方法：{task.method}</p>}
                          {task.materials && (
                            <p className="nx-task-note">所需物料：{task.materials}</p>
                          )}
                          {task.risk && (
                            <p className="nx-task-note nx-warning">风险与禁忌：{task.risk}</p>
                          )}
                          {task.review && <p className="nx-task-note">复查要点：{task.review}</p>}
                          {cards.length > 0 ? (
                            <p className="nx-task-evidence">
                              依据：
                              {cards.map((card) =>
                                card.url ? (
                                  <a
                                    key={card.id ?? card.title}
                                    href={card.url}
                                    target="_blank"
                                    rel="noreferrer"
                                  >
                                    {card.title || card.id}
                                    {card.reviewStatus === 'unverified' ? '（未核验草稿）' : ''}
                                  </a>
                                ) : (
                                  <span key={card.id ?? card.title}>
                                    {card.title || card.id}（无原文链接）
                                  </span>
                                ),
                              )}
                            </p>
                          ) : evidenceIds.length > 0 ? (
                            <p className="nx-task-evidence">依据来源ID：{evidenceIds.join('、')}</p>
                          ) : task.sourceMessageId ? (
                            <p className="nx-task-evidence nx-muted">依据：这条方案没有附来源ID</p>
                          ) : null}
                          {task.note && <p className="nx-task-note">{task.note}</p>}
                          {records.length > 0 && (
                            <>
                              <button
                                type="button"
                                className="nx-task-records-toggle"
                                aria-expanded={isOpen}
                                onClick={() => toggleRecords(task.id)}
                              >
                                执行与复查记录（{records.length}）
                              </button>
                              {isOpen && (
                                <ol className="nx-task-records">
                                  {records.map((record) => (
                                    <li key={record.id}>
                                      <b>{recordKindLabel(record.kind)}</b>
                                      <time>{formatDate(record.date)}</time>
                                      {outcomeText(record.outcome) && (
                                        <span className="nx-badge is-verified">
                                          {outcomeText(record.outcome)}
                                        </span>
                                      )}
                                      <p>{record.note}</p>
                                    </li>
                                  ))}
                                </ol>
                              )}
                            </>
                          )}
                          <div className="nx-task-actions">
                            {task.status === 'pending_confirmation' && (
                              <button
                                className="nx-button is-small is-primary"
                                disabled={acting}
                                onClick={() => setScheduleTarget(task)}
                              >
                                确认安排
                              </button>
                            )}
                            {task.status === 'pending' && (
                              <button
                                className="nx-button is-small is-primary"
                                disabled={acting}
                                onClick={() => openRecord(task, 'execution')}
                              >
                                提交执行记录
                              </button>
                            )}
                            {task.status === 'awaiting_review' && (
                              <button
                                className="nx-button is-small is-primary"
                                disabled={acting}
                                onClick={() => openRecord(task, 'review')}
                              >
                                提交复查记录
                              </button>
                            )}
                            {task.status === 'awaiting_review' && (
                              <button
                                className="nx-button is-small"
                                disabled={acting}
                                onClick={() =>
                                  void act(task, 'pending', '已退回待执行，可继续执行或调整安排。')
                                }
                              >
                                退回待执行
                              </button>
                            )}
                            {(task.status === 'completed' || task.status === 'cancelled') && (
                              <button
                                className="nx-button is-small"
                                disabled={acting}
                                onClick={() =>
                                  void act(task, 'pending', '任务已重新打开，回到待执行。')
                                }
                              >
                                重新打开
                              </button>
                            )}
                            {task.status === 'completed' && (
                              <button
                                className="nx-button is-small nx-task-next"
                                onClick={() => followUp(task)}
                              >
                                <Route size={15} />
                                据本次复查排下一步
                              </button>
                            )}
                            {(task.status === 'pending' ||
                              task.status === 'pending_confirmation') && (
                              <button
                                className="nx-button is-small"
                                disabled={acting}
                                onClick={() =>
                                  void act(task, 'cancelled', '任务已取消，历史记录保留。')
                                }
                              >
                                取消
                              </button>
                            )}
                            {OPEN_TASK_STATUSES.includes(task.status) && (
                              <button
                                className="nx-button is-small"
                                disabled={acting}
                                onClick={() => setScheduleTarget(task)}
                              >
                                <BellRing size={14} />
                                微信提醒
                              </button>
                            )}
                            <button className="nx-text-button" onClick={() => onDiscuss(task)}>
                              <MessageSquare size={14} />
                              在对话里讨论
                            </button>
                          </div>
                        </div>
                        <div className="nx-inline-actions">
                          <button
                            className="nx-icon-button"
                            aria-label={`编辑${task.title}`}
                            onClick={() => edit(task)}
                          >
                            <Pencil size={16} />
                          </button>
                          <button
                            className="nx-icon-button"
                            disabled={acting}
                            aria-label={`删除${task.title}`}
                            onClick={() => setDeleteTarget(task)}
                          >
                            <Trash2 size={16} />
                          </button>
                        </div>
                      </article>
                    );
                  })}
                </section>
              ))}
            </div>
          )}
        </>
      )}
      <Dialog
        open={!!editor}
        onOpenChange={(next) => {
          if (!next && !busy) setEditor(null);
        }}
      >
        <DialogContent className="nx-dialog" showCloseButton={!busy}>
          <DialogHeader>
            <DialogTitle>
              {editor && tasks.some((task) => task.id === editor.id)
                ? '编辑农事任务'
                : '记下一件农事'}
            </DialogTitle>
            <DialogDescription>
              计划日期用于整理任务；状态由执行与复查记录推进，不能在这里直接改完成。
            </DialogDescription>
          </DialogHeader>
          {editor && (
            <form className="nx-form" onSubmit={(event) => void save(event)}>
              <label className="nx-form-field">
                任务名称 *
                <input
                  autoFocus
                  required
                  maxLength={200}
                  placeholder="例如：检查南侧田块排水沟"
                  value={editor.title}
                  onChange={(event) => update('title', event.target.value)}
                />
              </label>
              <div className="nx-form-grid">
                <label className="nx-form-field">
                  计划日期（可留空待定）
                  <input
                    type="date"
                    min="1900-01-01"
                    max="2100-12-31"
                    value={editor.date}
                    onChange={(event) => update('date', event.target.value)}
                  />
                </label>
                <label className="nx-form-field">
                  关联田块
                  <NxSelect
                    ariaLabel="关联田块"
                    value={
                      fields.some((field) => field.id === editor.fieldId)
                        ? (editor.fieldId ?? '')
                        : ''
                    }
                    options={[
                      { value: '', label: '不关联田块' },
                      ...fields.map((field) => ({
                        value: field.id,
                        label: field.name,
                        hint: field.crop,
                      })),
                    ]}
                    onValueChange={(value) => update('fieldId', value)}
                    emptyText="还没有田块档案"
                  />
                </label>
              </div>
              <label className="nx-form-field">
                时间窗口
                <input
                  maxLength={200}
                  placeholder="例如：破口前 3—5 天；不清楚写「待确认」"
                  value={editor.timeWindow ?? ''}
                  onChange={(event) => update('timeWindow', event.target.value)}
                />
              </label>
              <label className="nx-form-field">
                执行条件
                <input
                  maxLength={8000}
                  placeholder="例如：雨停后、田间可以安全进入时"
                  value={editor.condition ?? ''}
                  onChange={(event) => update('condition', event.target.value)}
                />
              </label>
              <label className="nx-form-field">
                操作方法
                <textarea
                  rows={2}
                  maxLength={8000}
                  placeholder="选填，记录具体怎么做"
                  value={editor.method ?? ''}
                  onChange={(event) => update('method', event.target.value)}
                />
              </label>
              <label className="nx-form-field">
                所需物料
                <input
                  maxLength={2000}
                  placeholder="选填，例如：背负式喷雾器、清水；不清楚写「待确认」"
                  value={editor.materials ?? ''}
                  onChange={(event) => update('materials', event.target.value)}
                />
              </label>
              <label className="nx-form-field">
                风险与禁忌
                <input
                  maxLength={4000}
                  placeholder="选填，例如：避开高温时段与蜜蜂活动区"
                  value={editor.risk ?? ''}
                  onChange={(event) => update('risk', event.target.value)}
                />
              </label>
              <label className="nx-form-field">
                复查要点
                <input
                  maxLength={8000}
                  placeholder="选填，何时再看、观察什么"
                  value={editor.review ?? ''}
                  onChange={(event) => update('review', event.target.value)}
                />
              </label>
              <label className="nx-form-field">
                备注
                <textarea
                  rows={2}
                  maxLength={8000}
                  placeholder="记录执行情况，或下次需要留意的事"
                  value={editor.note ?? ''}
                  onChange={(event) => update('note', event.target.value)}
                />
              </label>
              <p className="nx-fine nx-muted">
                当前状态：{taskStatusLabel(editor)}。要标记完成，请在任务上提交执行记录与复查记录。
              </p>
              {error && (
                <p className="nx-error" role="alert">
                  {error}
                </p>
              )}
              <div className="nx-dialog-actions">
                <button
                  type="button"
                  className="nx-button"
                  disabled={busy}
                  onClick={() => setEditor(null)}
                >
                  取消
                </button>
                <button type="submit" className="nx-button is-primary" disabled={busy}>
                  {busy ? '正在保存…' : '保存任务'}
                </button>
              </div>
            </form>
          )}
          {editor && tasks.some((task) => task.id === editor.id) && (
            <TaskReminderPanel
              key={editor.id}
              task={tasks.find((task) => task.id === editor.id)!}
              settings={settings}
              onOpenWechat={onOpenWechat}
            />
          )}
        </DialogContent>
      </Dialog>
      {scheduleTarget && (
        <TaskScheduleDialog
          key={scheduleTarget.id}
          task={tasks.find((task) => task.id === scheduleTarget.id) ?? scheduleTarget}
          settings={settings}
          onOpenWechat={onOpenWechat}
          onConfirm={() => onStatus(scheduleTarget.id, 'pending')}
          onFinished={(withReminder) => {
            setScheduleTarget(null);
            setStatus(
              withReminder ? '任务安排与微信提醒已保存。' : '任务安排已确认，未新增微信提醒。',
            );
          }}
          onClose={() => setScheduleTarget(null)}
        />
      )}
      <Dialog
        open={!!recordTarget}
        onOpenChange={(next) => {
          if (!next && !busy) setRecordTarget(null);
        }}
      >
        <DialogContent className="nx-dialog nx-dialog-compact" showCloseButton={!busy}>
          <DialogHeader>
            <DialogTitle>
              {recordTarget?.kind === 'review' ? '提交复查记录' : '提交执行记录'}
            </DialogTitle>
            <DialogDescription>
              {recordTarget?.kind === 'review'
                ? '写下复查看到的情况与结论；提交后任务标记为已完成，农心会据此调整后续建议。'
                : '写下实际做了什么、什么时候做的；提交后任务进入待复查。'}
            </DialogDescription>
          </DialogHeader>
          {recordTarget && (
            <form className="nx-form" onSubmit={(event) => void submitRecord(event)}>
              <p className="nx-record-task">{recordTarget.task.title}</p>
              <label className="nx-form-field">
                日期
                <input
                  type="date"
                  min="1900-01-01"
                  max="2100-12-31"
                  value={recordDraft.date}
                  onChange={(event) =>
                    setRecordDraft((draft) => ({ ...draft, date: event.target.value }))
                  }
                />
              </label>
              <label className="nx-form-field">
                {recordTarget.kind === 'review' ? '复查看到什么 *' : '实际做了什么 *'}
                <textarea
                  autoFocus
                  required
                  rows={4}
                  maxLength={8000}
                  placeholder={
                    recordTarget.kind === 'review'
                      ? '例如：施药后 5 天查看，病斑没有扩展，新叶干净'
                      : '例如：上午按方案喷施，风力 2 级，用时 1.5 小时'
                  }
                  value={recordDraft.note}
                  onChange={(event) =>
                    setRecordDraft((draft) => ({ ...draft, note: event.target.value }))
                  }
                />
              </label>
              {recordTarget.kind === 'review' && (
                <div className="nx-form-field">
                  复查结论
                  <div className="nx-clarify-options">
                    {REVIEW_OUTCOMES.map((item) => (
                      <button
                        key={item.value}
                        type="button"
                        className={`nx-clarify-option${recordDraft.outcome === item.value ? ' is-selected' : ''}`}
                        aria-pressed={recordDraft.outcome === item.value}
                        onClick={() =>
                          setRecordDraft((draft) => ({
                            ...draft,
                            outcome: draft.outcome === item.value ? '' : item.value,
                          }))
                        }
                      >
                        {item.label}
                      </button>
                    ))}
                  </div>
                </div>
              )}
              {error && (
                <p className="nx-error" role="alert">
                  {error}
                </p>
              )}
              <div className="nx-dialog-actions">
                <button
                  type="button"
                  className="nx-button"
                  disabled={busy}
                  onClick={() => setRecordTarget(null)}
                >
                  取消
                </button>
                <button type="submit" className="nx-button is-primary" disabled={busy}>
                  {busy ? '正在提交…' : '提交记录'}
                </button>
              </div>
            </form>
          )}
        </DialogContent>
      </Dialog>
      <ConfirmDelete
        open={!!deleteTarget}
        title={`删除“${deleteTarget?.title ?? ''}”？`}
        description="这项任务和它的执行/复查记录都会被删除，删除后无法恢复。"
        onClose={() => setDeleteTarget(null)}
        onConfirm={async () => {
          if (deleteTarget) {
            await onDelete(deleteTarget.id);
            setStatus('任务已删除。');
          }
        }}
      />
    </section>
  );
}
