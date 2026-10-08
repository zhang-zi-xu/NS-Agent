import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../src/App';
import { requestHistory } from '@/features/chat/stream';
import {
  KEEP_RECENT_MESSAGES,
  SUMMARY_BUDGET,
  compactConversation,
  mergeSummaries,
  readConversationSummary,
  saveConversationSummary,
  clearConversationSummary,
  summaryCountText,
  summaryRequestMessage,
  summarizeHistory,
} from '@/features/chat/history-summary';
import type { ChatMessage, Conversation } from '@/features/chat/types';
import type { FieldProfile } from '@/features/fields/types';

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  localStorage.clear();
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const event = (name: string, body: unknown) => `event: ${name}\ndata: ${JSON.stringify(body)}\n\n`;
const complete = (reply: string) =>
  new Response(event('done', { reply }), { headers: { 'Content-Type': 'text/event-stream' } });

const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
const user = (id: string, content: string): ChatMessage => ({ id, role: 'user', content });
const reply = (id: string, content: string): ChatMessage => ({ id, role: 'assistant', content });

// ---- 纯函数：本地抽取式摘要 ----

test('摘要保留用户给出的事实（地区、作物、播期、数量、已执行操作），并被裁剪到预算内', () => {
  const messages: ChatMessage[] = [
    user('u1', '我在湖北荆州种水稻，6月1日播的，田里已经打过一次药。'),
    reply('a1', '建议先观察叶片和小穗，再决定是否补施。补充说明：这段回答故意写得比较长，用来验证助手消息只保留首句。'),
    user('u2', '每亩施了尿素10公斤。'),
  ];
  const summary = summarizeHistory(messages);
  assert.match(summary, /湖北荆州/);
  assert.match(summary, /水稻/);
  assert.match(summary, /尿素10公斤/);
  assert.match(summary, /早前对话摘要/);
  assert.ok(summary.length <= SUMMARY_BUDGET + 80, `摘要应接近预算内，实际 ${summary.length} 字`);

  const long = Array.from({ length: 60 }, (_, i) =>
    user(`m${i}`, `第${i}条：我在村里记录了作物和日期，用于压满预算。`),
  );
  const clipped = summarizeHistory(long);
  assert.ok(clipped.length <= SUMMARY_BUDGET + 80, `长列表也必须被裁剪，实际 ${clipped.length} 字`);
  assert.match(clipped, /第59条/, '应先保留最新的事实条目');
});

test('多次压缩累积成一段摘要，且不越过预算', () => {
  const first = summarizeHistory([user('u1', '我在湖北种水稻。')]);
  const second = mergeSummaries(first, [user('u2', '7月10日追肥一次。')]);
  const third = mergeSummaries(second, [user('u3', '田里已经排过一次水。')]);
  assert.match(third, /追肥/, '上一轮摘要的要点应保留');
  assert.match(third, /排过一次水/);
  assert.ok(third.length <= SUMMARY_BUDGET + 80, `累积后仍应在预算内，实际 ${third.length} 字`);
});

test('压缩只在达到上限时触发，并把窗口收敛到最近的消息', () => {
  const small: ChatMessage[] = [user('u1', '问题一'), reply('a1', '回答一')];
  const untouched = compactConversation(small, {});
  assert.equal(untouched.newlySummarized, 0);
  assert.equal(untouched.messages.length, 2);
  assert.equal(untouched.summary, '');

  const big: ChatMessage[] = Array.from({ length: 500 }, (_, i) =>
    i % 2 === 0 ? user(`u${i}`, `第${i}个问题`) : reply(`a${i}`, `第${i}个回答`),
  );
  const compacted = compactConversation(big, {});
  assert.equal(compacted.messages.length, KEEP_RECENT_MESSAGES);
  assert.equal(compacted.newlySummarized, 500 - KEEP_RECENT_MESSAGES);
  assert.equal(compacted.summarizedCount, 500 - KEEP_RECENT_MESSAGES);
  assert.ok(compacted.summary.length > 0);

  // 再次压缩：已经写进摘要的消息不重复计数
  const again = compactConversation(
    Array.from({ length: 500 }, (_, i) => user(`n${i}`, `新第${i}条`)),
    { summary: compacted.summary, summarizedCount: compacted.summarizedCount },
  );
  assert.ok(again.summarizedCount >= compacted.summarizedCount);
  assert.equal(again.messages.length, KEEP_RECENT_MESSAGES);
});

test('摘要作为请求历史开头的带标记消息，且明确说明可能不完整', () => {
  const summary = summarizeHistory([user('u1', '我在湖北种水稻，已打过一次药。')]);
  const marked = summaryRequestMessage(summary);
  assert.ok(marked);
  assert.equal(marked?.role, 'user');
  assert.match(marked!.content, /早前对话摘要（本地自动生成，可能不完整）/);
  assert.match(marked!.content, /可能不完整/);

  const history = requestHistory(
    [
      user('u1', '问题'),
      { id: 'x', role: 'assistant', content: '半截回答', status: 'interrupted' },
    ],
    summary,
  );
  assert.equal(history[0].content.includes('早前对话摘要'), true);
  assert.equal(
    history.some((m) => m.content === '半截回答'),
    false,
    '未完成的回答不得进入请求历史',
  );
});

test('摘要本地副本可保存、读取与清理，并有人可读的条数说明', () => {
  saveConversationSummary('conv-1', { summary: '【早前对话摘要】要点', summarizedCount: 12 });
  const stored = readConversationSummary('conv-1');
  assert.equal(stored?.count, 12);
  assert.match(stored?.text ?? '', /要点/);
  assert.match(summaryCountText(12), /12/);
  clearConversationSummary('conv-1');
  assert.equal(readConversationSummary('conv-1'), null);
});

// ---- 端到端：生成中"插话"排队 ----

type Request = { messages: Array<{ role: string; content: string }> };

async function fixture(
  responder: (call: number) => Response,
  initial: Conversation[] = [],
) {
  const original = fetch;
  const rows = new Map(initial.map((c) => [c.id, structuredClone(c)]));
  const requests: Request[] = [];
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
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA]);
    if (url === '/api/tasks' || url === '/api/knowledge') return json([]);
    if (url === '/api/conversations') return json([...rows.values()]);
    if (url.startsWith('/api/context?'))
      return json({
        location: '测试城市',
        observedAt: '2026-09-07T10:00',
        sources: { weather: 'mock', location: 'mock' },
      });
    if (url.startsWith('/api/conversations/')) {
      const id = url.split('/').at(-1)!;
      if (init.method === 'PUT') {
        const c = JSON.parse(String(init.body));
        rows.set(id, c);
        return json(c);
      }
    }
    if (url === '/api/chat/stream') {
      requests.push(JSON.parse(String(init.body)));
      return responder(requests.length);
    }
    throw new Error(`unexpected URL ${url}`);
  };
  render(<App />);
  await screen.findByText('Java 服务已连接');
  return {
    rows,
    requests,
    restore: () => {
      globalThis.fetch = original;
    },
  };
}

function ask(text: string) {
  fireEvent.change(screen.getByRole('textbox', { name: '向农心提问' }), {
    target: { value: text },
  });
  fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
}

test('生成中仍可输入：发送后排队，本轮结束后自动发出且只发一次', async () => {
  let stream!: ReadableStreamDefaultController<Uint8Array>;
  const f = await fixture((call) =>
    call > 1
      ? complete('第二个问题的回答')
      : new Response(
          new ReadableStream({
            start(c) {
              stream = c;
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
  );
  try {
    ask('第一个问题');
    await waitFor(() => assert.equal(f.requests.length, 1));

    // 生成中输入框仍可编辑，发送按钮变成"排队发送下一条"
    const box = screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement;
    assert.equal(box.disabled, false, '生成中不应禁用输入框');
    fireEvent.change(box, { target: { value: '第二个问题' } });
    fireEvent.click(screen.getByRole('button', { name: '排队发送下一条' }));
    await screen.findAllByText(/已排队/);
    assert.equal(f.requests.length, 1, '排队不等于立刻发请求');

    await act(async () => {
      stream.enqueue(new TextEncoder().encode(event('delta', { text: '先观察叶片' })));
      stream.enqueue(new TextEncoder().encode(event('done', { reply: '第一个问题的回答' })));
      stream.close();
    });

    await waitFor(() => assert.equal(f.requests.length, 2));
    assert.match(JSON.stringify(f.requests[1].messages), /第二个问题/);
    await screen.findByText('第二个问题的回答');
    assert.equal(screen.queryAllByText(/已排队/).length, 0, '发出后排队提示应消失');

    const saved = [...f.rows.values()][0];
    await waitFor(() =>
      assert.equal(
        saved.messages.filter((m) => m.role === 'user').length,
        2,
        '两条问题各存一次，不重复',
      ),
    );
  } finally {
    f.restore();
  }
});
