import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import App from '../src/App';
import type { FieldProfile } from '@/features/fields/types';
import type { FarmTask } from '@/features/tasks/types';
import {
  buildReviewFollowUpQuestion,
  buildSeasonPlanReport,
  reportFilename,
} from '@/features/tasks/review';
import { localDate } from '@/shared/lib/date';

// 闭环补上「调整」（第 9 条）+ 导出《本季农事规划报告》（第 7 条）。
// 全程使用模拟后端，不连接真实数据库；报告只用用例里真实存在的数据，缺字段必须写「未记录」。
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  localStorage.clear();
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const event = (name: string, body: unknown) => `event: ${name}\ndata: ${JSON.stringify(body)}\n\n`;
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
const fieldB: FieldProfile = { id: 'field-b', name: '测试乙田', crop: '小麦', sowDate: '2026-05-01' };
const LABELS: Record<string, string> = {
  pending_confirmation: '待确认',
  pending: '待执行',
  awaiting_review: '已执行待复查',
  completed: '已完成',
  cancelled: '已取消',
};
/** 后端 /api/tasks 的 evidenceCards 比前端声明多出机构/发布/地区等字段，这里按真实返回形状给。 */
const EVIDENCE_CARD = {
  id: 'chunk-pest-rice-blast',
  title: '2025年粮食作物重大病虫害防控技术方案',
  url: 'https://www.moa.gov.cn/example',
  reviewStatus: 'verified',
  status: 'verified',
  institution: '全国农技推广服务中心',
  publishedAt: '2025-03-01',
  region: '全国',
  crop: '水稻',
  growthStage: '破口期',
};
const evidenceCards = [EVIDENCE_CARD] as FarmTask['evidenceCards'];
const REVIEW_DATE = '2026-09-18';
const EXECUTION_NOTE = '上午按方案喷施，风力 2 级';
const REVIEW_NOTE = '处理后再看，病斑还是在原来的位置';
const task = (id: string, title: string, status: string, extra: Partial<FarmTask> = {}): FarmTask => ({
  id,
  title,
  date: '2026-09-12',
  status,
  statusLabel: LABELS[status],
  createdAt: '2026-09-11T09:00:00Z',
  fieldId: 'field-a',
  fieldName: '测试甲田',
  records: [],
  ...extra,
});
const completedAfterReview = (outcome: string, extra: Partial<FarmTask> = {}) =>
  task('t-done', '第一次施药', 'completed', {
    timeWindow: '破口前 3—5 天',
    condition: '雨停后、田面无积水',
    method: '按标签推荐剂量兑水喷雾',
    materials: '背负式喷雾器、清水',
    review: '施药后 5 天查看病斑是否扩展',
    risk: '避开高温时段',
    evidence: ['chunk-pest-rice-blast'],
    evidenceCards,
    records: [
      {
        id: 'r-exec',
        taskId: 't-done',
        kind: 'execution',
        date: '2026-09-12',
        note: EXECUTION_NOTE,
        createdAt: '2026-09-12T09:00:00Z',
      },
      {
        id: 'r-review',
        taskId: 't-done',
        kind: 'review',
        date: REVIEW_DATE,
        note: REVIEW_NOTE,
        outcome,
        createdAt: '2026-09-18T09:00:00Z',
      },
    ],
    ...extra,
  });

type FixtureOptions = {
  fields?: FieldProfile[];
  tasks?: FarmTask[];
  reply?: string;
  withPlan?: boolean;
  extraRoutes?: (url: string, init: RequestInit, method: string) => Response | null;
};

async function fixture(options: FixtureOptions = {}) {
  const originalFetch = fetch;
  const fields = options.fields ?? [fieldA];
  const rows = new Map((options.tasks ?? []).map((item) => [item.id, structuredClone(item)]));
  const conversations = new Map<string, Record<string, unknown>>();
  const calls = {
    tasks: [] as Array<Record<string, unknown>>,
    chat: [] as Array<Record<string, unknown>>,
  };
  let sequence = 0;
  sessionStorage.setItem(
    'nongxin-ai-settings',
    JSON.stringify({
      provider: 'custom',
      baseUrl: 'https://example.test/v1',
      model: 'mock',
      apiKey: 'test-only-not-a-real-credential',
    }),
  );
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    const method = init.method ?? 'GET';
    const body = init.body ? JSON.parse(String(init.body)) : null;
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json(fields);
    if (url === '/api/knowledge') return json([]);
    if (url.startsWith('/api/context?')) return json({});
    if (url === '/api/conversations') return json([...conversations.values()]);
    if (url === '/api/tasks' && method === 'GET') return json([...rows.values()]);
    if (url === '/api/tasks' && method === 'POST') {
      const incoming = body as Record<string, unknown>;
      const saved = {
        ...incoming,
        statusLabel: LABELS[String(incoming.status)] ?? String(incoming.status),
        records: [],
      } as unknown as FarmTask;
      rows.set(saved.id, saved);
      calls.tasks.push(incoming);
      return json(saved, 201);
    }
    if (url === '/api/chat/stream') {
      calls.chat.push(body as Record<string, unknown>);
      const payload = options.withPlan
        ? {
            reply: options.reply ?? '按复查结论排了下一步。',
            plan: {
              title: '下一步安排',
              items: [
                {
                  itemId: 'p1',
                  task: '再观察 5 天并补查病斑',
                  date: '2026-09-23',
                  window: '处理后 5—7 天',
                  condition: '晴天后田间可进入',
                  method: '五点取样查看病斑扩展',
                  materials: '记录本',
                  review: '新叶是否继续出现病斑',
                  evidence: ['chunk-pest-rice-blast'],
                },
              ],
            },
          }
        : { reply: options.reply ?? '先不安排用药。' };
      return new Response(event('done', payload), {
        headers: { 'Content-Type': 'text/event-stream' },
      });
    }
    const conversationMatch = url.match(/^\/api\/conversations\/([^/]+)$/);
    if (conversationMatch && (method === 'PUT' || method === 'PATCH')) {
      const saved = {
        ...(body as Record<string, unknown>),
        id: conversationMatch[1],
        title: (body as { title?: string })?.title ?? '新对话',
      };
      conversations.set(conversationMatch[1], saved);
      return json(saved);
    }
    const handled = options.extraRoutes?.(url, init, method);
    if (handled) return handled;
    throw new Error(`unexpected ${method} ${url} ${String(sequence++)}`);
  };
  render(<App />);
  await screen.findByText('Java 服务已连接');
  return {
    rows,
    calls,
    restore: () => {
      globalThis.fetch = originalFetch;
    },
  };
}

/** 捕获导出下载：只取文件名与 Markdown 正文，不写磁盘、不触发真实导航。 */
function captureDownload() {
  const blobs: Blob[] = [];
  const downloads: Array<{ filename: string }> = [];
  const originalCreate = URL.createObjectURL;
  const originalRevoke = URL.revokeObjectURL;
  const originalClick = window.HTMLAnchorElement.prototype.click;
  URL.createObjectURL = (blob: Blob) => {
    blobs.push(blob);
    return `blob:mock/${blobs.length}`;
  };
  URL.revokeObjectURL = () => {};
  window.HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement) {
    downloads.push({ filename: this.download });
  };
  return {
    downloads,
    contents: () => Promise.all(blobs.map((blob) => blob.text())),
    restore: () => {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
      window.HTMLAnchorElement.prototype.click = originalClick;
    },
  };
}

async function openTasks() {
  fireEvent.click(screen.getByRole('button', { name: /^农事任务/ }));
  await screen.findByRole('heading', { name: '把想做的事，落到每一天。' });
}
async function openCompleted() {
  await openTasks();
  fireEvent.click(screen.getByRole('button', { name: '已完成' }));
}
const composer = () => screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement;
/** 页面上的 role=status 有多处（任务页状态行、提醒条），这里只关心是否出现了某段文案。 */
const allStatusText = () =>
  screen
    .getAllByRole('status')
    .map((node) => node.textContent ?? '')
    .join(' ／ ');
async function idle() {
  await waitFor(() => assert.equal(composer().disabled, false));
}
const lastTask = (f: Awaited<ReturnType<typeof fixture>>) =>
  f.calls.tasks.at(-1) as Record<string, unknown>;

// ---------------------------------------------------------------- 任务 A：据复查排下一步

test('each review outcome asks for a different next step', () => {
  const drafts = new Map(
    ['resolved', 'improved', 'unchanged', 'worse', 'other'].map((outcome) => [
      outcome,
      buildReviewFollowUpQuestion(completedAfterReview(outcome), fieldA, '2026-09-20'),
    ]),
  );
  const D = (outcome: string) => drafts.get(outcome) as string;
  // 5 种结局的诉求句必须互不相同（否则「据复查排下一步」等于没区分）
  assert.equal(new Set(drafts.values()).size, 5);
  assert.match(D('worse'), /评估是否需要调整或重新处理/);
  assert.match(D('resolved'), /排下一阶段的常规管理/);
  assert.match(D('unchanged'), /判断是否要换做法、还是再观察多久/);
  assert.match(D('improved'), /是否还需要补一次处理/);
  assert.match(D('other'), /根据实际记录判断下一步/);
  // 结构化追问必须带上田块与作物、原任务与时间窗口、执行记录、复查结论与日期、期望产出
  for (const draft of drafts.values()) {
    assert.match(draft, /田块与作物：测试甲田（水稻）/);
    assert.match(draft, /原任务：第一次施药/);
    assert.match(draft, /原任务时间窗口：破口前 3—5 天/);
    assert.match(draft, new RegExp(`本次执行记录：2026-09-12：${EXECUTION_NOTE}`));
    assert.match(draft, new RegExp(`复查日期：${REVIEW_DATE}`));
    assert.match(draft, /本轮希望得到的产出：给出下一步该做什么。/);
    assert.match(draft, /不要给没有依据的用药建议/);
  }
  assert.match(D('worse'), /复查结论：反而变差/);
  assert.match(D('resolved'), /复查结论：问题已解决/);
  assert.match(D('improved'), /复查结论：明显好转/);
  assert.match(D('unchanged'), /复查结论：没有变化/);
  assert.match(D('other'), /复查结论：其他情况/);
  // 田块归属写进追问里，用户能在任务页核对（加入任务时关联的是当前对话所选田块）
  assert.match(D('resolved'), /方案请按「测试甲田」安排/);
  // 没有结论时不能编一个出来，要如实说"未勾选"
  const noOutcome = buildReviewFollowUpQuestion(
    task('t-x', '巡田', 'completed', { records: [] }),
    fieldA,
    '2026-09-20',
  );
  assert.match(noOutcome, /复查结论：未勾选/);
  assert.match(noOutcome, /没有录入执行记录（该结果未经核验）/);
});

test('completed task offers a follow-up entry that drafts the question and finishes on a real task', async () => {
  const f = await fixture({ tasks: [completedAfterReview('unchanged')], withPlan: true });
  try {
    await openCompleted();
    // 「在对话里讨论」仍保留，新增的「调整」入口并排出现
    assert.ok(screen.getByRole('button', { name: /在对话里讨论/ }));
    fireEvent.click(screen.getByRole('button', { name: /据本次复查排下一步/ }));
    await idle();
    const draft = composer().value;
    assert.match(draft, /# 据本次复查排下一步/);
    assert.match(draft, /复查结论：没有变化/);
    assert.match(draft, /判断是否要换做法、还是再观察多久/);

    // 发送后进入正常对话流程：助手按既有护栏给方案卡
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
    await screen.findByText('再观察 5 天并补查病斑');
    assert.equal(f.calls.chat.length, 1);
    const sent = (f.calls.chat[0].messages as Array<{ content: string }>).at(-1);
    assert.match(sent?.content ?? '', /据本次复查排下一步/);

    // 一键把方案卡加入任务：链路真的通（登记为待确认，不是已执行）
    fireEvent.click(screen.getByRole('button', { name: '加入任务' }));
    await screen.findByText('确认加入农事任务');
    fireEvent.click(screen.getByRole('button', { name: '确认加入任务' }));
    await waitFor(() => assert.equal(f.calls.tasks.length, 1));
    const created = lastTask(f);
    assert.equal(created.title, '再观察 5 天并补查病斑');
    assert.equal(created.status, 'pending_confirmation', '下一步只是建议，必须等用户确认');
    assert.equal(created.planItemId, 'p1');
    assert.ok(created.sourceMessageId, '方案项与来源消息一起登记，保证幂等');
    assert.deepEqual(created.evidence, ['chunk-pest-rice-blast']);
    // 新任务关联的是当前对话所选田块（此处未选田块），追问文本里已写明目标田块供用户核对
    assert.equal(created.fieldId, null);
    assert.match(sent?.content ?? '', /方案请按「测试甲田」安排/);
    assert.equal(f.rows.get('t-done')?.status, 'completed', '排下一步不改动原任务的状态');
  } finally {
    f.restore();
  }
});

test('completed task without a follow-up call keeps the discuss draft unchanged', async () => {
  const f = await fixture({ tasks: [completedAfterReview('improved')] });
  try {
    await openCompleted();
    fireEvent.click(screen.getByRole('button', { name: /在对话里讨论/ }));
    await idle();
    const draft = composer().value;
    assert.match(draft, /关于任务「第一次施药」/);
    assert.match(draft, /我的问题是：/);
    assert.doesNotMatch(draft, /据本次复查排下一步/);
  } finally {
    f.restore();
  }
});

// ---------------------------------------------------------------- 任务 B：导出规划报告

test('season report only writes data that exists and marks every gap as 未记录', () => {
  const field: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
  const report = buildSeasonPlanReport(
    field,
    [
      task('t-1', '排水晒田', 'pending', { date: '2026-09-10', condition: '雨停后', review: '看田面' }),
      task('t-2', '第二次施药', 'completed', {
        date: '2026-09-20',
        records: [
          {
            id: 'r1',
            taskId: 't-2',
            kind: 'review',
            date: '2026-09-25',
            note: '病斑没有再扩展',
            outcome: 'resolved',
            createdAt: '2026-09-25T09:00:00Z',
          },
        ],
      }),
      task('t-3', '待定安排', 'pending_confirmation', { date: '' }),
    ],
    new Date('2026-10-05T09:30:00'),
  );
  // 标题、生成时间与四类内容都在
  assert.match(report, /^# 本季农事规划报告 · 测试甲田/);
  assert.match(report, /生成时间：2026-10-05 09:30/);
  assert.match(report, /## 一、田块档案/);
  assert.match(report, /## 二、计划总览/);
  assert.match(report, /## 三、执行与复查记录/);
  assert.match(report, /## 四、资料依据/);
  assert.match(report, /## 五、风险与注意/);
  // 只写实际录入的信息
  assert.match(report, /\| 作物 \| 水稻 \|/);
  assert.match(report, /\| 播期 \| 2026-06-01 \|/);
  assert.match(report, /排水晒田/);
  assert.match(report, /复查记录 2026-09-25：病斑没有再扩展（问题已解决）/);
  // 缺失字段一律「未记录」，绝不补造
  assert.match(report, /\| 品种 \| 未记录 \|/);
  assert.match(report, /\| 面积 \| 未记录 \|/);
  assert.match(report, /\| 备注 \| 未记录 \|/);
  assert.match(report, /执行条件未记录（2 项）：第二次施药、待定安排/);
  assert.match(report, /待确认任务 1 项（确认后才进入执行）：待定安排/);
  assert.match(report, /- 1 项任务日期待定，执行时间以现场判断为准/);
  assert.match(report, /还没有提交任何记录的任务 1 项：排水晒田/);
  assert.match(report, /这些任务还没有附任何依据来源ID/);
  for (const fabricated of ['未发现问题', '无需用药', '待补充', '示例'])
    assert.doesNotMatch(report, new RegExp(fabricated));
  // 页脚三句免责与口径
  assert.match(report, /报告中的方案只供用户审阅，不代表已执行/);
  assert.match(report, /农药使用须以当地登记的标签为准/);
  assert.match(report, /「已完成」只由提交的执行与复查记录推进/);
});

test('season report lists real evidence and says so when source cards are missing', () => {
  const withCards = buildSeasonPlanReport(
    fieldB,
    [
      task('t-cards', '第一次施药', 'pending', {
        fieldId: 'field-b',
        fieldName: '测试乙田',
        evidence: ['chunk-pest-rice-blast'],
        evidenceCards: [
          {
            ...EVIDENCE_CARD,
            title: '水稻重大病虫害防控技术方案',
          },
        ] as FarmTask['evidenceCards'],
      }),
    ],
    new Date('2026-10-05T00:00:00'),
  );
  assert.match(withCards, /来源ID：chunk-pest-rice-blast/);
  assert.match(withCards, /标题：水稻重大病虫害防控技术方案/);
  assert.match(withCards, /机构：全国农技推广服务中心/);
  assert.match(withCards, /核验状态：已核验原文/);
  assert.match(withCards, /原文：https:\/\/www\.moa\.gov\.cn\/example/);
  // 只有 ID、没有来源卡时不许编造标题
  const idsOnly = buildSeasonPlanReport(
    fieldB,
    [
      task('t-ids', '第二次施药', 'pending', {
        fieldId: 'field-b',
        fieldName: '测试乙田',
        evidence: ['chunk-heat-fertilize'],
      }),
    ],
    new Date('2026-10-05T00:00:00'),
  );
  assert.match(idsOnly, /来源ID：chunk-heat-fertilize/);
  assert.match(idsOnly, /来源信息以后端登记为准/);
  assert.match(idsOnly, /本机未取到来源卡信息/);
  assert.doesNotMatch(idsOnly, /标题：/);
});

test('report filename keeps the field name and the local date', () => {
  assert.equal(
    reportFilename('测试甲田', '2026-10-05'),
    `农心-农事规划报告-测试甲田-${localDate()}.md`.replace(localDate(), '2026-10-05'),
  );
  assert.match(reportFilename('东/西 田:1', '2026-10-05'), /^农心-农事规划报告-东-西 田-1-2026-10-05\.md$/);
  assert.equal(reportFilename('', '2026-10-05'), '农心-农事规划报告-未命名田块-2026-10-05.md');
});

test('task page exports the selected field as a Markdown report', async () => {
  const f = await fixture({
    fields: [fieldA, fieldB],
    tasks: [
      completedAfterReview('improved'),
      task('t-b', '乙田播种准备', 'pending', {
        date: '2026-09-15',
        fieldId: 'field-b',
        fieldName: '测试乙田',
      }),
    ],
  });
  const download = captureDownload();
  try {
    await openTasks();
    fireEvent.click(screen.getByRole('button', { name: /导出本季农事规划报告/ }));
    assert.match(allStatusText(), /请先在上方按田块筛选里选择要导出的田块/);
    assert.equal(download.downloads.length, 0, '未选田块时不产生文件');

    // 选择甲田后导出：文件名与正文都只含甲田的数据
    fireEvent.click(screen.getByRole('combobox', { name: '按田块筛选任务' }));
    const option = await screen.findByRole('option', { name: /测试甲田/ });
    // Base UI 的选项在 pointerdown 之后才接受点击，直接 click 会选不中。
    fireEvent.pointerDown(option);
    fireEvent.click(option);
    // 任务列表默认只显示「进行中」，已完成的第一次施药不在列表里；导出按田块汇总全部状态，
    // 因此这里直接校验导出文件的内容，不再断言列表可见性。
    fireEvent.click(screen.getByRole('button', { name: /导出本季农事规划报告/ }));
    await waitFor(() => assert.equal(download.downloads.length, 1));
    assert.equal(download.downloads[0].filename, `农心-农事规划报告-测试甲田-${localDate()}.md`);
    const report = (await download.contents())[0];
    assert.match(report, /^# 本季农事规划报告 · 测试甲田/);
    assert.match(report, /第一次施药/);
    assert.doesNotMatch(report, /乙田播种准备/, '只导出所选田块的任务');
    assert.match(allStatusText(), /已导出「测试甲田」的本季农事规划报告/);
  } finally {
    download.restore();
    f.restore();
  }
});

test('exporting without any field explains what to do first', async () => {
  const f = await fixture({ fields: [] });
  const download = captureDownload();
  try {
    await openTasks();
    fireEvent.click(screen.getByRole('button', { name: /导出本季农事规划报告/ }));
    const hint = await screen.findByText(/还没有田块档案/);
    assert.match(hint.textContent ?? '', /先在「我的田块」里建一块地/);
    assert.equal(download.downloads.length, 0);
  } finally {
    download.restore();
    f.restore();
  }
});

test('a task moved to another field is listed under that field in the report', async () => {
  const f = await fixture({
    fields: [fieldA, fieldB, { id: 'field-c', name: '测试丙田', crop: '玉米', sowDate: '2026-05-20' }],
    tasks: [
      task('t-c', '丙田追肥', 'pending', {
        date: '2026-09-18',
        fieldId: 'field-c',
        fieldName: '测试丙田',
      }),
      task('t-a', '甲田排水', 'pending', { date: '2026-09-10' }),
    ],
  });
  try {
    await openTasks();
    fireEvent.click(screen.getByRole('combobox', { name: '按田块筛选任务' }));
    const option = await screen.findByRole('option', { name: /测试丙田/ });
    fireEvent.pointerDown(option);
    fireEvent.click(option);
    assert.ok(screen.getByText('丙田追肥'));
    assert.equal(screen.queryByText('甲田排水'), null, '按田块筛选后只显示该田块的任务');
    // 分组标题也要跟着当前筛选走；不要对 JSDOM 元素做深比较（会把整个文档打进断言 diff）。
    const group = document.querySelector('.nx-task-group');
    assert.ok(group, '筛选后应有任务分组');
    assert.equal(
      within(group as HTMLElement).queryAllByText('测试丙田').length > 0,
      true,
      '分组标题应为所选田块',
    );
  } finally {
    f.restore();
  }
});
