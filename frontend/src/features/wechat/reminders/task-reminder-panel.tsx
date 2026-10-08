import { useEffect, useRef, useState } from 'react';
import type { AiSettings } from '@/features/settings/types';
import type { FarmTask } from '@/features/tasks/types';
import { api, errorText } from '@/shared/api/client';
import { beijingInput, defaultReminder, reminderInstant } from './time';
import {
  REMINDER_LABELS,
  type ReminderListing,
  type ReminderSuggestion,
  type TaskReminder,
} from './types';

export function TaskReminderPanel({
  task,
  settings,
  onOpenWechat,
  confirmation,
}: {
  task: FarmTask;
  settings?: AiSettings;
  onOpenWechat?: () => void;
  /** 任务确认和提醒分别保存；后者失败时保留草稿，只重试提醒，不重复推进任务。 */
  confirmation?: {
    onConfirm: () => Promise<unknown>;
    onFinished: (withReminder: boolean) => void;
    onBusyChange: (busy: boolean) => void;
  };
}) {
  const [listing, setListing] = useState<ReminderListing | null>(null);
  const [row, setRow] = useState<TaskReminder | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [time, setTime] = useState('');
  const [reason, setReason] = useState('');
  const [consent, setConsent] = useState('');
  const [error, setError] = useState('');
  const [connectionError, setConnectionError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [suggesting, setSuggesting] = useState(false);
  const [duplicateConsent, setDuplicateConsent] = useState(false);
  const initialized = useRef(false);
  const dirty = useRef(false);
  const editing = useRef(false);
  const epoch = useRef(0);
  const baseVersion = useRef(0);
  const alive = useRef(true);
  const saving = useRef(false);
  const confirmed = useRef(task.status !== 'pending_confirmation');
  const closed = ['completed', 'cancelled'].includes(task.status);
  const ready = listing?.connection.connected && listing.connection.contextReady;
  const local = ['localhost', '127.0.0.1'].includes(window.location.hostname);

  useEffect(() => {
    alive.current = true;
    if (!local) return;
    let inFlight = false;
    const controller = new AbortController();
    async function load() {
      if (inFlight) return;
      inFlight = true;
      try {
        const next = await api<ReminderListing>('/wechat/reminders', { signal: controller.signal });
        if (!alive.current || controller.signal.aborted) return;
        if (!next?.connection || !Array.isArray(next.reminders))
          throw new Error('无法读取微信提醒状态。');
        const value = next.reminders.find((r) => r.taskId === task.id) ?? null;
        setListing(next);
        setRow(value);
        setConnectionError('');
        setConsent((current) => (current === next.connection.bindingId ? current : ''));
        if (!initialized.current) {
          initialized.current = true;
          baseVersion.current = value?.version ?? 0;
          setEnabled(!!value && !['ACCEPTED', 'CANCELLED'].includes(value.state));
          setTime(value ? beijingInput(value.remindAt) : defaultReminder(task.date));
        } else if (!editing.current) {
          baseVersion.current = value?.version ?? 0;
          if (value) setTime(beijingInput(value.remindAt));
        }
      } catch (cause) {
        if (alive.current && !controller.signal.aborted) {
          setConnectionError(errorText(cause));
          setListing(null);
        }
      } finally {
        inFlight = false;
      }
    }
    void load();
    const timer = window.setInterval(() => void load(), 5000);
    return () => {
      alive.current = false;
      epoch.current++;
      controller.abort();
      window.clearInterval(timer);
    };
  }, [task.id, task.date, local]);

  async function enable() {
    if (!listing || closed || saving.current) return;
    setEnabled(true);
    setNotice('尚未保存，确认时间后点击“保存提醒”。');
    setError('');
    setConsent('');
    dirty.current = false;
    editing.current = true;
    baseVersion.current = row?.version ?? 0;
    setTime(defaultReminder(task.date));
    setReason('默认在任务日期当天北京时间 09:00 提醒；没有未来默认时间时请自行设置。');
    const requestEpoch = ++epoch.current;
    if (!settings?.apiKey.trim()) return;
    setSuggesting(true);
    try {
      const suggestion = await api<ReminderSuggestion>('/wechat/reminders/suggest', {
        method: 'POST',
        body: JSON.stringify({ taskId: task.id, settings }),
      });
      if (alive.current && epoch.current === requestEpoch && !dirty.current) {
        setTime(suggestion.remindAt ? beijingInput(suggestion.remindAt) : '');
        setReason(suggestion.reason);
      }
    } catch {
      if (alive.current && epoch.current === requestEpoch && !dirty.current)
        setReason('AI 建议暂不可用，请确认默认时间或自行设置。');
    } finally {
      if (alive.current && epoch.current === requestEpoch) setSuggesting(false);
    }
  }
  async function perform(action: 'save' | 'cancel' | 'retry') {
    if (saving.current) return;
    const saveReminder = action === 'save' && (!confirmation || enabled);
    const at = reminderInstant(time);
    if (saveReminder && (!at || Date.parse(at) <= Date.now())) {
      setError('请选择未来的北京时间。');
      return;
    }
    if (saveReminder && (!ready || !consent || consent !== listing?.connection.bindingId)) {
      setError('请先建立微信会话，并确认将任务摘要发送给当前扫码账号。');
      return;
    }
    saving.current = true;
    setBusy(true);
    confirmation?.onBusyChange(true);
    setError('');
    epoch.current++;
    setSuggesting(false);
    try {
      if (action === 'save') {
        if (confirmation && !confirmed.current) {
          await confirmation.onConfirm();
          confirmed.current = true;
        }
        if (saveReminder) {
          const saved = await api<TaskReminder>(
            `/wechat/reminders/${encodeURIComponent(task.id)}`,
            {
              method: 'PUT',
              body: JSON.stringify({
                remindAt: at,
                version: baseVersion.current,
                bindingId: consent,
                consent: !!consent,
              }),
            },
          );
          if (!alive.current) return;
          setRow(saved);
          baseVersion.current = saved.version;
          setNotice('提醒已保存，将按该时间发送；可再次修改或关闭。');
        }
        if (!alive.current) return;
        confirmation?.onFinished(saveReminder);
      } else if (action === 'cancel' && row) {
        await api(`/wechat/reminders/${encodeURIComponent(task.id)}?version=${row.version}`, {
          method: 'DELETE',
        });
        if (!alive.current) return;
        setRow({ ...row, state: 'CANCELLED', version: row.version + 1 });
        baseVersion.current = row.version + 1;
        setEnabled(false);
        setNotice('已取消后续提醒。已经提交给微信的消息无法撤回。');
      } else if (action === 'retry' && row) {
        await api(`/wechat/reminders/${encodeURIComponent(task.id)}/retry`, {
          method: 'POST',
          body: JSON.stringify({ version: row.version, acknowledgeDuplicate: duplicateConsent }),
        });
        if (!alive.current) return;
        setRow({ ...row, state: 'PENDING', version: row.version + 1 });
        baseVersion.current = row.version + 1;
        setDuplicateConsent(false);
        setNotice('已加入补发队列。');
      }
      dirty.current = false;
      editing.current = false;
    } catch (cause) {
      if (alive.current)
        setError(
          confirmation && confirmed.current && saveReminder
            ? `任务已确认，但微信提醒未确认保存成功：${errorText(cause)}。请核对提醒状态后重试；时间草稿已保留。`
            : errorText(cause),
        );
    } finally {
      saving.current = false;
      confirmation?.onBusyChange(false);
      if (alive.current) setBusy(false);
    }
  }
  function disable() {
    if (saving.current) return;
    epoch.current++;
    setSuggesting(false);
    if (row && !['ACCEPTED', 'CANCELLED'].includes(row.state)) {
      void perform('cancel');
      return;
    }
    setEnabled(false);
    setConsent('');
    editing.current = false;
    setNotice('未保存的提醒已关闭。');
  }
  const confirmationAction = confirmation && (
    <div className="nx-dialog-actions">
      <button
        type="button"
        className="nx-button is-primary"
        disabled={
          busy || closed || (enabled && (!ready || !consent || !time || row?.state === 'SENDING'))
        }
        onClick={() => void perform('save')}
      >
        {busy ? '正在保存…' : enabled ? '确认安排并保存提醒' : '确认安排（不新增微信提醒）'}
      </button>
    </div>
  );
  if (!local)
    return (
      <section className="nx-task-reminder">
        <b>微信提醒</b>
        <p>请在运行农心的电脑上设置微信提醒。</p>
        {error && <p role="alert">{error}</p>}
        {confirmationAction}
      </section>
    );
  return (
    <section className="nx-task-reminder" aria-label="任务微信提醒">
      <div className="nx-reminder-heading">
        <b>微信提醒</b>
        <label>
          <input
            type="checkbox"
            aria-label="设置微信提醒"
            checked={enabled}
            disabled={busy || closed || !listing || (!confirmation && !enabled && !ready)}
            onChange={(event) => (event.target.checked ? void enable() : disable())}
          />
          启用
        </label>
      </div>
      <p>依据已保存的任务内容设置。任务资料有改动时，请先保存任务再调整提醒。</p>
      {row && (
        <p role="status">
          {REMINDER_LABELS[row.state]} · {beijingInput(row.remindAt).replace('T', ' ')} 北京时间
        </p>
      )}
      {closed ? (
        <p>任务已结束，未发送的提醒会取消；重新打开任务不会恢复旧提醒。</p>
      ) : (
        <>
          {!ready && (
            <p>
              {listing?.connection.connected
                ? '请先在微信向农心发送一条消息，建立提醒会话。'
                : '请先在本机连接微信，再向农心发送一条消息。'}{' '}
              {onOpenWechat && (
                <button type="button" className="nx-text-button" onClick={onOpenWechat}>
                  连接微信
                </button>
              )}
            </p>
          )}
          {row && !row.accountMatches && !['ACCEPTED', 'CANCELLED'].includes(row.state) && (
            <p className="nx-warning">
              该提醒绑定原扫码账号。请恢复原账号，或重新授权并保存给当前账号。
            </p>
          )}
          {(enabled || confirmation) && (
            <div className="nx-reminder-fields">
              <label className="nx-form-field">
                提醒时间（北京时间）
                <input
                  type="datetime-local"
                  value={time}
                  disabled={!enabled || busy || row?.state === 'SENDING'}
                  onChange={(event) => {
                    if (!editing.current) baseVersion.current = row?.version ?? 0;
                    editing.current = true;
                    dirty.current = true;
                    setTime(event.target.value);
                    setReason('已采用你手动指定的时间。');
                    setNotice('时间已修改，保存后生效。');
                  }}
                />
              </label>
              {confirmation && !enabled && <p>勾选“启用”后设置时间；不勾选不会新增微信提醒。</p>}
              {enabled && !time && <p>任务日期缺失或当天 09:00 已过去，请自行选择未来时间。</p>}
              {suggesting && <p>正在整理时间建议，你可以直接修改时间。</p>}
              {reason && <p>{reason}</p>}
              {enabled && (
                <label className="nx-reminder-consent">
                  <input
                    type="checkbox"
                    checked={!!consent && consent === listing?.connection.bindingId}
                    disabled={!ready || busy}
                    onChange={(event) =>
                      setConsent(event.target.checked ? (listing?.connection.bindingId ?? '') : '')
                    }
                  />
                  允许将此任务摘要发送给当前扫码微信账号
                </label>
              )}
              {!confirmation && (
                <button
                  type="button"
                  className="nx-button is-small is-primary"
                  disabled={busy || !ready || !consent || !time || row?.state === 'SENDING'}
                  onClick={() => void perform('save')}
                >
                  保存提醒
                </button>
              )}
            </div>
          )}
          {row && ['FAILED', 'UNKNOWN'].includes(row.state) && (
            <div className="nx-reminder-fields">
              <label className="nx-reminder-consent">
                <input
                  type="checkbox"
                  checked={duplicateConsent}
                  onChange={(event) => setDuplicateConsent(event.target.checked)}
                />
                我已核对微信，接受重试可能产生重复提醒
              </label>
              <button
                type="button"
                className="nx-button is-small"
                disabled={busy || !ready || !row.accountMatches || !duplicateConsent}
                onClick={() => void perform('retry')}
              >
                重新尝试发送
              </button>
            </div>
          )}
        </>
      )}
      {notice && <p role="status">{notice}</p>}
      {(error || connectionError) && (
        <p role="alert" className="nx-warning">
          {error || connectionError}
        </p>
      )}
      <p className="nx-muted">
        电脑与服务需保持运行。断线错过的提醒恢复后合并补发；接口接受不代表已读。
        {settings?.apiKey.trim() ? '启用时会使用自有 Key 生成一次时间建议，可能产生模型费用。' : ''}
      </p>
      {confirmationAction}
    </section>
  );
}
