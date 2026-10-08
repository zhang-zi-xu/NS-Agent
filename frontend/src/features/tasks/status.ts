import type { FarmTask } from './types';

/** 状态文案与语气色：以后端返回的 statusLabel 为准，仅在这里补样式。 */
export const OPEN_TASK_STATUSES = ['pending_confirmation', 'pending', 'awaiting_review'];
const TASK_STATUS_TONE: Record<string, string> = {
  pending_confirmation: 'is-draft',
  pending: 'is-open',
  awaiting_review: 'is-review',
  completed: 'is-done',
  cancelled: 'is-cancelled',
};
export const taskStatusLabel = (task: FarmTask) => task.statusLabel || task.status;
export const taskStatusTone = (task: FarmTask) => TASK_STATUS_TONE[task.status] ?? 'is-open';
