import type { ChatMessage } from '@/features/chat/types';

/**
 * 长会话的本地抽取式摘要：不调用模型、不发网络请求，全部是纯函数。
 * 用途只有一个——把因为「只带最近 20 条」和「对话最多 500 条」而滚出上下文的早前消息，
 * 压成一段带明显标记的中文摘要，避免用户对窗口用尽完全无感知。
 */

/** 摘要正文（不含标题行）的字符预算。标题行与后续标记另算，保证整段摘要远小于单条 20,000 字符上限。 */
export const SUMMARY_BUDGET = 520;
/** 单条用户消息保留的上限：问句主干通常都很短，原句短片段比"概括"更不容易丢事实。 */
export const USER_ENTRY_LIMIT = 70;
/** 单条助手消息保留的上限：首句或前 60 字，避免旧回答把预算吃光。 */
export const ASSISTANT_ENTRY_LIMIT = 60;
/** 摘要里优先保留的是最新滚出的条目：预算不够时从最旧的开始丢弃。 */
/** 消息达到这个条数时触发本地压缩（服务端 PUT 上限为 500 条）。 */
export const COMPRESS_MESSAGE_LIMIT = 490;
/**
 * 压缩后本地保留的最近消息条数。
 * 取 240 是权衡结果：对话区会把这些消息全部渲染成 DOM，条数越多长会话越卡；
 * 真正进入模型请求的仍然只有最近 20 条有效消息，240 条足够覆盖界面回看与"重试这条问题"。
 */
export const KEEP_RECENT_MESSAGES = 240;
/** 服务端 ConversationController 对一次保存的消息条数上限。 */
export const MAX_SAVED_MESSAGES = 500;
const SUMMARY_PREFIX = '【早前对话摘要（本地自动生成，可能不完整）】';
const SUMMARY_END_MARKER = '【摘要结束】';
const SUMMARY_HEADER = `${SUMMARY_PREFIX}早期对话因上下文窗口有限已被压缩，以下为抽取式要点：`;
const PREVIOUS_MARK = '· 此前摘要：';
/** 合并时留一段配额放"上一轮摘要"，越早的要点越可能在这一步被后加的短片段挤掉。 */
const PREVIOUS_SHARE = 0.4;

/**
 * 用户明确给出的事实：地区、作物、播期、已执行操作、数量等。
 * 含这些标记的那一句优先整句保留；不确定的表述宁可留下原句短片段，也不改写成模型口吻。
 */
const FACT_MARKERS: RegExp[] = [
  /\d/,
  /(?:亩|分地|公顷|hm2|hm²|平方米|平方|垄|畦|块地|田块|地块|大棚|温室|果园|菜地|山地|坡地)/,
  /(?:水稻|小麦|玉米|大豆|油菜|马铃薯|花生|棉花|蔬菜|黄瓜|番茄|辣椒|白菜|萝卜|果树|苹果|柑橘|茶树|秧苗|秧田)/,
  /(?:月|日|号|播|种|栽|插秧|移栽|定植|施|打药|喷|灌|浇|排水|追肥|底肥|除草|收割|收获|晒|覆膜|通风)/,
  /(?:已|已经|刚|昨天|前天|今天|上周|上个月|去年|今年|前天|用了|买了|打了|施了|浇了|种了|收了|执行了)/,
  /(?:省|市|县|区|镇|乡|村|屯|组)/,
];
const MIN_FACT_SEGMENT = 8;
const ELLIPSIS = '……';

function squeeze(text: string): string {
  return text.replace(/[\t\u3000]+/g, ' ').replace(/ {2,}/g, ' ').trim();
}

function clip(text: string, limit: number): string {
  const value = squeeze(text);
  if (value.length <= limit) return value;
  return value.slice(0, Math.max(1, limit - 1)) + ELLIPSIS;
}

/** 取第一条事实（含数字/日期/地区/作物/农事动作等标记）的短片段；没有标记时退回句首。 */
export function factSnippet(text: string, limit: number): string {
  const value = squeeze(text);
  if (!value) return '';
  for (const segment of value.split(/(?<=[。！？；;!?\n])/)) {
    const piece = segment.trim();
    if (piece.length >= MIN_FACT_SEGMENT && FACT_MARKERS.some((marker) => marker.test(piece)))
      return clip(piece, limit);
  }
  return clip(value, limit);
}

/** 助手消息只留首句或前 60 字：旧回答是参考，不是事实来源。 */
function replySnippet(text: string, limit: number): string {
  const value = squeeze(text);
  if (!value) return '';
  const first = value.split(/(?<=[。！？!?\n])/)[0]?.trim() ?? '';
  return clip(first || value, limit);
}

export type SummaryOptions = {
  budget?: number;
  userLimit?: number;
  assistantLimit?: number;
  previous?: string;
};

function buildEntry(message: ChatMessage, options: SummaryOptions): string {
  if (message.role === 'user')
    return `· 用户说：${factSnippet(message.content, options.userLimit ?? USER_ENTRY_LIMIT)}`;
  return `· 农心答：${replySnippet(message.content, options.assistantLimit ?? ASSISTANT_ENTRY_LIMIT)}`;
}

/** 按预算从最旧开始裁剪，保证整段摘要不超过预算（宁可少写几条，也不改写已有短片段）。 */
function fitBudget(header: string, lines: string[], budget: number): string | null {
  const usable: string[] = [];
  let used = header.length;
  // 从最新的一条往前放；装不下的都是更早的条目，直接丢弃。
  for (let index = lines.length - 1; index >= 0; index--) {
    const line = lines[index];
    if (used + 1 + line.length > budget) break;
    usable.push(line);
    used += 1 + line.length;
  }
  usable.reverse();
  return usable.length ? `${header}\n${usable.join('\n')}` : null;
}

/** 把一批即将滚出窗口的早前消息压成一段 ≤ 600 字的中文摘要；没有可压缩内容时返回空串。 */
export function summarizeHistory(messages: ChatMessage[], options: SummaryOptions = {}): string {
  const budget = options.budget ?? SUMMARY_BUDGET;
  const entries = messages
    .filter(
      (message) =>
        (message.role === 'user' || message.role === 'assistant') &&
        typeof message.content === 'string' &&
        !!message.content.trim(),
    )
    .map((message) => buildEntry(message, options));
  if (!entries.length) return '';
  const previous = squeeze(options.previous ?? '');
  const lines = previous
    ? [`${PREVIOUS_MARK}${clip(previous, Math.floor(budget * PREVIOUS_SHARE))}`, ...entries]
    : entries;
  return fitBudget(SUMMARY_HEADER, lines, budget) ?? clip(previous, budget);
}

/** 增量压缩：把"当前摘要 + 本次滚出的消息"合并成新摘要，多条逐次累积也不会越过预算。 */
export function mergeSummaries(previous: string, messages: ChatMessage[]): string {
  return summarizeHistory(messages, { previous });
}

export type Compacted = {
  /** 压缩后保留的最近消息（保持原有顺序） */
  messages: ChatMessage[];
  /** 下一次请求开头的摘要：上一轮摘要 + 这次滚出的消息 */
  summary: string;
  /** 摘要中已覆盖的早前消息条数（含此前各次压缩的累计值） */
  summarizedCount: number;
  /** 这次新滚出并写入摘要的消息条数（0 表示没有触发压缩） */
  newlySummarized: number;
};

/**
 * 本地压缩：消息达到 COMPRESS_MESSAGE_LIMIT 时把更早的消息换成摘要，只留最近 KEEP_RECENT_MESSAGES 条。
 * 已经写进上一轮摘要的消息靠 id 排除，避免同一段原文被反复压缩、反复增长。
 */
export function compactConversation(
  messages: ChatMessage[],
  info: { summary?: string; summarizedCount?: number } = {},
): Compacted {
  const previous = squeeze(info.summary ?? '');
  const counted = Math.max(0, info.summarizedCount ?? 0);
  if (messages.length < COMPRESS_MESSAGE_LIMIT || messages.length <= KEEP_RECENT_MESSAGES)
    return { messages, summary: previous, summarizedCount: counted, newlySummarized: 0 };
  const alreadyCovered = new Set(
    messages.slice(0, Math.min(counted, messages.length)).map((message) => message.id),
  );
  const dropped = messages
    .slice(0, Math.max(0, messages.length - KEEP_RECENT_MESSAGES))
    .filter((message) => !alreadyCovered.has(message.id));
  const retained = messages.slice(-KEEP_RECENT_MESSAGES);
  const nextSummary = dropped.length ? mergeSummaries(previous, dropped) : previous;
  return {
    messages: retained,
    summary: nextSummary,
    summarizedCount: counted + dropped.length,
    newlySummarized: dropped.length,
  };
}

export function summaryCountText(count: number): string {
  return `已自动压缩 ${count} 条早前消息，可在设置/详情里查看摘要`;
}

/** 会话摘要的本地持久化：服务端只存 id/标题/田块/消息/创建时间，额外字段会被忽略，所以摘要存在浏览器里。 */
const STORAGE_KEY = 'nongxin-conversation-summaries';
const STORAGE_LIMIT = 20;

type StoredSummary = { text: string; count: number };

function readStore(): Record<string, StoredSummary> {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as unknown;
    return value && typeof value === 'object' ? (value as Record<string, StoredSummary>) : {};
  } catch {
    return {};
  }
}

export function readConversationSummary(
  conversationId: string,
): { text: string; count: number } | null {
  const stored = readStore()[conversationId];
  if (!stored || typeof stored.text !== 'string' || !stored.text) return null;
  return { text: stored.text, count: typeof stored.count === 'number' ? stored.count : 0 };
}

export function saveConversationSummary(
  conversationId: string,
  value: { summary: string; summarizedCount: number },
): void {
  if (!value.summary) return;
  try {
    const store = readStore();
    delete store[conversationId];
    const entries = Object.entries(store).slice(-(STORAGE_LIMIT - 1));
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        ...Object.fromEntries(entries),
        [conversationId]: { text: value.summary, count: value.summarizedCount },
      }),
    );
  } catch {
    /* 本地存储不可用时只影响"刷新后仍能看到摘要"，不阻塞对话。 */
  }
}

export function clearConversationSummary(conversationId: string): void {
  try {
    const store = readStore();
    delete store[conversationId];
    localStorage.setItem(STORAGE_KEY, JSON.stringify(store));
  } catch {
    /* 同上：清理失败不影响对话本身。 */
  }
}

/** 请求历史开头的那条摘要消息：带明显标记，且明确标注"可能不完整"，不让模型当作完整记录使用。 */
export function summaryRequestMessage(summary: string): { role: 'user'; content: string } | null {
  const text = squeeze(summary);
  if (!text) return null;
  return {
    role: 'user',
    content: `${text}\n${SUMMARY_END_MARKER}以上摘要由本地规则生成，可能不完整；请当作背景参考，关键事实仍以用户原话为准。`,
  };
}
