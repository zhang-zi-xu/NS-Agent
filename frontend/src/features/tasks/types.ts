export type TaskRecord = {
  id: string;
  taskId: string;
  fieldId?: string | null;
  /** execution = 执行记录；review = 复查记录 */
  kind: string;
  date: string;
  note: string;
  outcome?: string | null;
  sourceMessageId?: string | null;
  createdAt: string;
};

export type FarmTask = {
  id: string;
  title: string;
  date: string;
  /** 待确认 / 待执行 / 已执行待复查 / 已完成 / 取消（与后端 TaskStatus 一致） */
  status: string;
  statusLabel?: string | null;
  createdAt: string;
  fieldId?: string | null;
  fieldName?: string | null;
  condition?: string | null;
  method?: string | null;
  review?: string | null;
  note?: string | null;
  timeWindow?: string | null;
  materials?: string | null;
  risk?: string | null;
  evidence?: string[];
  evidenceCards?: Array<{ id?: string; title?: string; url?: string; reviewStatus?: string }>;
  planItemId?: string | null;
  sourceMessageId?: string | null;
  confirmedAt?: string | null;
  executedAt?: string | null;
  completedAt?: string | null;
  records?: TaskRecord[];
};
