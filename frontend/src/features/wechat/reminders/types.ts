export type ReminderState =
  | 'PENDING'
  | 'WAITING'
  | 'SENDING'
  | 'ACCEPTED'
  | 'FAILED'
  | 'UNKNOWN'
  | 'CANCELLED';
export type TaskReminder = {
  taskId: string;
  remindAt: string;
  version: number;
  state: ReminderState;
  attempts: number;
  accountMatches: boolean;
};
export type ReminderListing = {
  connection: { connected: boolean; contextReady: boolean; bindingId?: string };
  reminders: TaskReminder[];
};
export type ReminderSuggestion = {
  remindAt?: string | null;
  reason: string;
  source: 'default' | 'ai';
};

export const REMINDER_LABELS: Record<ReminderState, string> = {
  PENDING: '待发送',
  WAITING: '等待原微信账号连接或会话恢复',
  SENDING: '正在发送',
  ACCEPTED: '微信接口已接受（不代表已读）',
  FAILED: '发送失败',
  UNKNOWN: '发送结果不确定',
  CANCELLED: '已取消',
};
