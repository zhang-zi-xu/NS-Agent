import { useState } from 'react';
import type { FarmTask } from '../types';
import type { AiSettings } from '@/features/settings/types';
import { TaskReminderPanel } from '@/features/wechat/reminders/task-reminder-panel';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/shared/ui/dialog';

/** 登记后的逐项确认入口；复用提醒校验、授权和保存逻辑，不在草稿上启动调度。 */
export function TaskScheduleDialog({
  task,
  remaining = 1,
  settings,
  onOpenWechat,
  onConfirm,
  onFinished,
  onClose,
}: {
  task: FarmTask;
  remaining?: number;
  settings?: AiSettings;
  onOpenWechat?: () => void;
  onConfirm: () => Promise<unknown>;
  onFinished: (withReminder: boolean) => void;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <Dialog open onOpenChange={(open) => !open && !busy && onClose()}>
      <DialogContent className="nx-dialog" showCloseButton={!busy}>
        <DialogTitle>确认农事安排与微信提醒</DialogTitle>
        <DialogDescription>
          任务已登记，但不代表已执行。微信提醒需要你确认时间及发送授权后才会保存。
          {remaining > 1 ? `还有 ${remaining} 项待逐一确认，各项时间独立设置。` : ''}
        </DialogDescription>
        <div className="nx-task-schedule-summary">
          <b>{task.title}</b>
          <p>
            计划日期：{task.date || '日期待定'} · 田块：{task.fieldName || '未关联田块'}
          </p>
          {task.timeWindow && <p>时间窗口：{task.timeWindow}</p>}
          {task.condition && <p>执行条件：{task.condition}</p>}
        </div>
        <TaskReminderPanel
          key={task.id}
          task={task}
          settings={settings}
          onOpenWechat={onOpenWechat}
          confirmation={{ onConfirm, onFinished, onBusyChange: setBusy }}
        />
        <button type="button" className="nx-text-button" disabled={busy} onClick={onClose}>
          稍后安排
        </button>
      </DialogContent>
    </Dialog>
  );
}
