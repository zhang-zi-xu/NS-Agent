import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen, waitFor, act } from '@testing-library/react';
import { TaskReminderPanel } from '@/features/wechat/reminders/task-reminder-panel';
import { beijingInput, defaultReminder, reminderInstant } from '@/features/wechat/reminders/time';
import type { FarmTask } from '@/features/tasks/types';
import type { TaskReminder } from '@/features/wechat/reminders/types';

const originalFetch = globalThis.fetch;
afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});
const task: FarmTask = {
  id: 'test-task',
  title: '测试农事',
  date: '2090-10-10',
  status: 'pending',
  createdAt: '',
};
const settings = {
  provider: 'deepseek',
  model: 'test-model',
  baseUrl: '',
  apiKey: 'test-only-key-not-in-ui',
};
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
function fixture(row: TaskReminder | null = null, suggest?: () => Promise<Response>) {
  const writes: Array<{ path: string; method: string; body: Record<string, unknown> }> = [];
  globalThis.fetch = (async (input, init) => {
    const path = String(input);
    const method = init?.method ?? 'GET';
    if (method === 'GET')
      return json({
        connection: { connected: true, contextReady: true, bindingId: 'binding-one' },
        reminders: row ? [row] : [],
      });
    const body = init?.body ? JSON.parse(String(init.body)) : {};
    writes.push({ path, method, body });
    if (path.endsWith('/suggest'))
      return suggest
        ? suggest()
        : json({ remindAt: '2090-10-10T00:00:00Z', source: 'ai', reason: '已有时段' });
    if (method === 'PUT')
      return json({
        taskId: task.id,
        remindAt: body.remindAt,
        version: 1,
        state: 'PENDING',
        attempts: 0,
        accountMatches: true,
      });
    return json({ queued: true });
  }) as typeof fetch;
  return writes;
}
async function enable() {
  const checkbox = await screen.findByRole('checkbox', { name: '设置微信提醒' });
  await waitFor(() => assert.equal((checkbox as HTMLInputElement).disabled, false));
  fireEvent.click(checkbox);
}

test('reminder time helpers use Beijing time independently of system timezone', () => {
  assert.equal(reminderInstant('2090-10-10T09:00'), '2090-10-10T01:00:00.000Z');
  assert.equal(beijingInput('2090-10-10T01:00:00Z'), '2090-10-10T09:00');
  assert.equal(reminderInstant('2026-02-30T09:00'), null);
  assert.equal(defaultReminder('', 0), '');
  assert.equal(defaultReminder('2000-01-01'), '');
  assert.equal(defaultReminder('2090-10-10'), '2090-10-10T09:00');
});

test('late AI advice cannot overwrite user input and no reminder is armed before explicit save', async () => {
  let resolve!: (value: Response) => void;
  const pending = new Promise<Response>((done) => {
    resolve = done;
  });
  const writes = fixture(null, () => pending);
  render(<TaskReminderPanel task={task} settings={settings} />);
  await enable();
  const input = screen.getByLabelText('提醒时间（北京时间）') as HTMLInputElement;
  fireEvent.change(input, { target: { value: '2090-10-11T07:30' } });
  await act(async () => {
    resolve(json({ remindAt: '2090-10-10T00:00:00Z', source: 'ai', reason: '迟到建议' }));
  });
  assert.equal(input.value, '2090-10-11T07:30');
  assert.equal(writes.filter((call) => call.method === 'PUT').length, 0);
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  fireEvent.click(screen.getByRole('button', { name: '保存提醒' }));
  await screen.findByText('提醒已保存，将按该时间发送；可再次修改或关闭。');
  const sent = writes.find((call) => call.method === 'PUT')!;
  assert.equal(sent.body.remindAt, '2090-10-10T23:30:00.000Z');
  assert.equal(sent.body.bindingId, 'binding-one');
  assert.equal(sent.body.consent, true);
  assert.equal(document.body.textContent?.includes(settings.apiKey), false);
});

test('saving while suggestion is pending freezes the chosen time', async () => {
  let resolve!: (value: Response) => void;
  const writes = fixture(
    null,
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  render(<TaskReminderPanel task={task} settings={settings} />);
  await enable();
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  fireEvent.click(screen.getByRole('button', { name: '保存提醒' }));
  await screen.findByText('提醒已保存，将按该时间发送；可再次修改或关闭。');
  await act(async () =>
    resolve(json({ remindAt: '2090-10-11T00:00:00Z', source: 'ai', reason: 'late' })),
  );
  assert.equal(
    (screen.getByLabelText('提醒时间（北京时间）') as HTMLInputElement).value,
    '2090-10-10T09:00',
  );
  assert.equal(writes.filter((call) => call.method === 'PUT').length, 1);
});

test('missing Key keeps default without a model request, and absent date stays blank', async () => {
  const writes = fixture();
  render(<TaskReminderPanel task={{ ...task, date: '' }} />);
  await enable();
  assert.equal((screen.getByLabelText('提醒时间（北京时间）') as HTMLInputElement).value, '');
  assert.equal(
    (screen.getByRole('button', { name: '保存提醒' }) as HTMLButtonElement).disabled,
    true,
  );
  assert.equal(writes.length, 0);
});

test('provider failure retains the editable rule default', async () => {
  fixture(null, async () => {
    throw new Error('provider-error');
  });
  render(<TaskReminderPanel task={task} settings={settings} />);
  await enable();
  await screen.findByText('AI 建议暂不可用，请确认默认时间或自行设置。');
  assert.equal(
    (screen.getByLabelText('提醒时间（北京时间）') as HTMLInputElement).value,
    '2090-10-10T09:00',
  );
});

test('uncertain send requires explicit duplicate acknowledgement before manual retry', async () => {
  const writes = fixture({
    taskId: task.id,
    remindAt: '2090-10-10T01:00:00Z',
    version: 4,
    state: 'UNKNOWN',
    attempts: 0,
    accountMatches: true,
  });
  render(<TaskReminderPanel task={task} />);
  const retry = await screen.findByRole('button', { name: '重新尝试发送' });
  assert.equal((retry as HTMLButtonElement).disabled, true);
  fireEvent.click(screen.getByLabelText('我已核对微信，接受重试可能产生重复提醒'));
  fireEvent.click(retry);
  await screen.findByText('已加入补发队列。');
  assert.deepEqual(writes.at(-1)?.body, { version: 4, acknowledgeDuplicate: true });
});

test('turning an active reminder off cancels the persisted version', async () => {
  const writes = fixture({
    taskId: task.id,
    remindAt: '2090-10-10T01:00:00Z',
    version: 4,
    state: 'PENDING',
    attempts: 0,
    accountMatches: true,
  });
  render(<TaskReminderPanel task={task} />);
  await waitFor(() =>
    assert.equal(
      (screen.getByRole('checkbox', { name: '设置微信提醒' }) as HTMLInputElement).checked,
      true,
    ),
  );
  fireEvent.click(screen.getByRole('checkbox', { name: '设置微信提醒' }));
  await screen.findByText('已取消后续提醒。已经提交给微信的消息无法撤回。');
  assert.equal(writes[0].method, 'DELETE');
  assert.match(writes[0].path, /version=4$/);
});

test('confirmation exposes time and saves the authorized reminder after confirming the task', async () => {
  const writes = fixture();
  let confirmed = 0;
  let finished: boolean | undefined;
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          confirmed++;
        },
        onFinished: (value) => {
          finished = value;
        },
        onBusyChange: () => {},
      }}
    />,
  );
  assert.ok(screen.getByLabelText('提醒时间（北京时间）'));
  await enable();
  fireEvent.change(screen.getByLabelText('提醒时间（北京时间）'), {
    target: { value: '2090-10-12T18:15' },
  });
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  fireEvent.click(screen.getByRole('button', { name: '确认安排并保存提醒' }));
  await waitFor(() => assert.equal(finished, true));
  assert.equal(confirmed, 1);
  assert.deepEqual(writes.at(-1)?.body, {
    remindAt: '2090-10-12T10:15:00.000Z',
    version: 0,
    bindingId: 'binding-one',
    consent: true,
  });
});

test('confirmation without opting in never arms a reminder', async () => {
  const writes = fixture();
  let finished: boolean | undefined;
  let confirmed = 0;
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          confirmed++;
        },
        onFinished: (value) => {
          finished = value;
        },
        onBusyChange: () => {},
      }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '确认安排（不新增微信提醒）' }));
  await waitFor(() => assert.equal(finished, false));
  assert.equal(confirmed, 1);
  assert.equal(writes.length, 0);
});

test('invalid reminder time cannot confirm the task or arm a reminder', async () => {
  const writes = fixture();
  let confirmed = 0;
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          confirmed++;
        },
        onFinished: () => assert.fail('must not finish'),
        onBusyChange: () => {},
      }}
    />,
  );
  await enable();
  fireEvent.change(screen.getByLabelText('提醒时间（北京时间）'), {
    target: { value: '2000-10-10T09:00' },
  });
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  fireEvent.click(screen.getByRole('button', { name: '确认安排并保存提醒' }));
  await screen.findByText('请选择未来的北京时间。');
  assert.equal(confirmed, 0);
  assert.equal(writes.length, 0);
});

test('reminder failure retains input; retry does not repeat task confirmation', async () => {
  const writes = fixture();
  const succeed = globalThis.fetch;
  let fail = true;
  globalThis.fetch = async (input, init) =>
    fail && init?.method === 'PUT'
      ? json({ error: '模拟提醒保存失败' }, 503)
      : succeed(input, init);
  let confirmed = 0;
  let finished = false;
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          confirmed++;
        },
        onFinished: () => {
          finished = true;
        },
        onBusyChange: () => {},
      }}
    />,
  );
  await enable();
  fireEvent.change(screen.getByLabelText('提醒时间（北京时间）'), {
    target: { value: '2090-10-12T06:45' },
  });
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  fireEvent.click(screen.getByRole('button', { name: '确认安排并保存提醒' }));
  await screen.findByText(/任务已确认，但微信提醒未确认保存成功/);
  assert.equal(finished, false);
  assert.equal(
    (screen.getByLabelText('提醒时间（北京时间）') as HTMLInputElement).value,
    '2090-10-12T06:45',
  );
  fail = false;
  fireEvent.click(screen.getByRole('button', { name: '确认安排并保存提醒' }));
  await waitFor(() => assert.equal(finished, true));
  assert.equal(confirmed, 1);
  assert.equal(writes.filter((call) => call.method === 'PUT').length, 1);
});

test('task confirmation failure never saves the reminder', async () => {
  const writes = fixture();
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          throw new Error('任务状态冲突');
        },
        onFinished: () => assert.fail('must not finish'),
        onBusyChange: () => {},
      }}
    />,
  );
  await enable();
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  fireEvent.click(screen.getByRole('button', { name: '确认安排并保存提醒' }));
  await screen.findByText('任务状态冲突');
  assert.equal(writes.length, 0);
});

test('unavailable WeChat allows a time draft but never saves or confirms an opted-in reminder', async () => {
  const writes = fixture();
  const connectedFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) =>
    (init?.method ?? 'GET') === 'GET'
      ? json({ connection: { connected: false, contextReady: false }, reminders: [] })
      : connectedFetch(input, init);
  let confirmed = 0;
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          confirmed++;
        },
        onFinished: () => assert.fail('must not finish'),
        onBusyChange: () => {},
      }}
    />,
  );
  await enable();
  fireEvent.change(screen.getByLabelText('提醒时间（北京时间）'), {
    target: { value: '2090-10-12T08:00' },
  });
  assert.equal(
    (screen.getByRole('button', { name: '确认安排并保存提醒' }) as HTMLButtonElement).disabled,
    true,
  );
  assert.ok(screen.getByText(/请先在本机连接微信/));
  assert.equal(confirmed, 0);
  assert.equal(writes.length, 0);
});

test('repeated confirmation clicks share one in-flight save', async () => {
  const writes = fixture();
  let release!: () => void;
  let confirmed = 0;
  let finished = 0;
  render(
    <TaskReminderPanel
      task={{ ...task, status: 'pending_confirmation' }}
      confirmation={{
        onConfirm: async () => {
          confirmed++;
          await new Promise<void>((resolve) => {
            release = resolve;
          });
        },
        onFinished: () => {
          finished++;
        },
        onBusyChange: () => {},
      }}
    />,
  );
  await enable();
  fireEvent.click(screen.getByLabelText('允许将此任务摘要发送给当前扫码微信账号'));
  const submit = screen.getByRole('button', { name: '确认安排并保存提醒' });
  fireEvent.click(submit);
  fireEvent.click(submit);
  assert.equal(confirmed, 1);
  await act(async () => release());
  await waitFor(() => assert.equal(finished, 1));
  assert.equal(writes.filter((call) => call.method === 'PUT').length, 1);
});
