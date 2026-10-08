import { Check } from 'lucide-react';
import { SettingsDialog } from '@/features/settings/components/settings-dialog';
import { MobileDialog } from '@/features/mobile/components/mobile-dialog';
import { WechatDialog } from '@/features/wechat/components/wechat-dialog';
import { TaskScheduleDialog } from '@/features/tasks/components/task-schedule-dialog';

import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/shared/ui/dialog';

import type { WorkspaceModel } from '../use-workspace';

export function WorkspaceDialogs({ workspace }: { workspace: WorkspaceModel }) {
  const {
    activeField,
    settings,
    settingsOpen,
    setSettingsOpen,
    mobileOpen,
    setMobileOpen,
    wechatOpen,
    setWechatOpen,
    planTarget,
    setPlanTarget,
    planBusy,
    planError,
    applySettings,
    rememberSettings,
    addPlan,
    tasks,
    scheduleQueue,
    setScheduleQueue,
    changeTaskStatus,
    setNotice,
  } = workspace;
  return (
    <>
      <SettingsDialog
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        settings={settings}
        onSave={applySettings}
        onAutoSave={rememberSettings}
      />
      <MobileDialog open={mobileOpen} onOpenChange={setMobileOpen} />
      <WechatDialog
        open={wechatOpen}
        onOpenChange={setWechatOpen}
        settings={settings}
        onOpenSettings={() => {
          setWechatOpen(false);
          setSettingsOpen(true);
        }}
      />
      <Dialog
        open={!!planTarget}
        onOpenChange={(open) => {
          if (!open && !planBusy) setPlanTarget(null);
        }}
      >
        <DialogContent className="nx-dialog" showCloseButton={!planBusy}>
          <DialogTitle>确认加入农事任务</DialogTitle>
          <DialogDescription>
            这只是建议清单，不代表已执行。登记后会逐项确认农事安排与微信提醒时间；不启用提醒则不会推送。
            日期不明确的项目会保留为“待定日期”。
          </DialogDescription>
          <ul className="nx-plan-confirm">
            {planTarget?.plan?.items?.map((item, i) => (
              <li key={i}>
                <Check size={15} />
                <span>{item.task}</span>
                <small>{item.date || '日期待定'}</small>
              </li>
            ))}
          </ul>
          <p className="nx-muted">关联田块：{activeField?.name || '不关联田块'}</p>
          {planError && (
            <p className="nx-error" role="alert">
              {planError}
            </p>
          )}
          <div className="nx-dialog-actions">
            <button disabled={planBusy} className="nx-button" onClick={() => setPlanTarget(null)}>
              再想想
            </button>
            <button
              disabled={planBusy}
              className="nx-button is-primary"
              onClick={() => void addPlan()}
            >
              {planBusy ? '正在保存…' : '确认加入任务'}
            </button>
          </div>
        </DialogContent>
      </Dialog>
      {scheduleQueue[0] && (
        <TaskScheduleDialog
          task={tasks.find((task) => task.id === scheduleQueue[0].id) ?? scheduleQueue[0]}
          remaining={scheduleQueue.length}
          settings={settings}
          onOpenWechat={() => setWechatOpen(true)}
          onConfirm={() => changeTaskStatus(scheduleQueue[0].id, 'pending')}
          onFinished={(withReminder) => {
            setScheduleQueue((queue) => queue.slice(1));
            setNotice(
              withReminder ? '任务安排与微信提醒已保存。' : '任务安排已确认，未新增微信提醒。',
            );
          }}
          onClose={() => setScheduleQueue([])}
        />
      )}
    </>
  );
}
