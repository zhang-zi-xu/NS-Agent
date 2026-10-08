import { useEffect, useRef, useState, type ClipboardEvent as ReactClipboardEvent } from 'react';
import { api, errorText, uid } from '@/shared/api/client';
import { localDate as dateToday } from '@/shared/lib/date';
import type { AiSettings } from '@/features/settings/types';
import type { FieldProfile } from '@/features/fields/types';
import type { LiveContext } from '@/features/context/types';
import type { AttachedImage } from '@/features/images/types';
import { imageFilesFrom, uploadPhoto } from '@/features/images/uploads';
import type { ChatContext, ChatMessage, Conversation } from './types';
import type { ClarifyAnswers } from './clarification';
import {
  compactConversation,
  MAX_SAVED_MESSAGES,
  readConversationSummary,
  saveConversationSummary,
  clearConversationSummary,
} from './history-summary';
import { captureContext, requestHistory, retryMessages, streamChat } from './stream';

const newConversation = (fieldId: string | null = null): Conversation => ({
  id: uid(),
  title: '新对话',
  fieldId,
  messages: [],
  createdAt: new Date().toISOString(),
});
const lastLine = (text: string) =>
  text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .at(-1)
    ?.slice(0, 80) ?? '';

type ChatWorkspaceOptions = {
  fields: FieldProfile[];
  settings: AiSettings;
  context: LiveContext | null;
  ready: boolean;
  view: string;
  onOpenChat: () => void;
  setNotice: (message: string) => void;
  setSettingsOpen: (open: boolean) => void;
};

/** 生成中按下发送时冻结的一条待发消息：连同当时的田块/天气快照、附件与照片一起排队。 */
type QueuedMessage = {
  text: string;
  context: ChatContext;
  /** 排队时输入框里的农情数据附件（发送时不再取当前草稿，避免张冠李戴） */
  attachment: string | null;
  images: AttachedImage[];
};

const queuePreview = (text: string) => (text.length > 40 ? `${text.slice(0, 40)}…` : text);

/** Owns the whole chat lifecycle: one send lock, one ordered save queue, and isolated conversation drafts. */
export function useChatWorkspace({
  fields,
  settings,
  context,
  ready,
  view,
  onOpenChat,
  setNotice,
  setSettingsOpen,
}: ChatWorkspaceOptions) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [current, setCurrent] = useState<Conversation>(() => newConversation());
  /**
   * 生成结束后的自动发送（排队消息）会在上一轮 send 的闭包里执行，
   * 那里的 current 是旧快照。用它拼消息会把上一轮内容覆盖掉，所以统一读这个 ref。
   */
  const currentRef = useRef(current);
  currentRef.current = current;

  const [query, setQuery] = useState('');
  const [attachment, setAttachment] = useState<string | null>(null);
  const [images, setImages] = useState<AttachedImage[]>([]);
  /** 当前模型是否按"能看图"处理（由后端名单 + 用户设置决定），仅用于提前提示，不阻止发问 */
  const [visionReady, setVisionReady] = useState(true);
  /** 当前田块的田间照片档案 + 是否随问题自动带上最近几张（用户可在作曲区取消） */
  const [fieldPhotos, setFieldPhotos] = useState<AttachedImage[]>([]);
  const [autoFieldPhotos, setAutoFieldPhotos] = useState(true);
  const [imageError, setImageError] = useState('');
  const [imageBusy, setImageBusy] = useState(false);

  /** 选文件 / 粘贴 / 拖入 都走这里：压缩去 EXIF → 上传 → 挂到待发送列表（最多 3 张） */
  async function addPhotos(files: File[]) {
    if (!files.length || sendingRef.current) return;
    setImageError('');
    const room = 3 - images.length;
    if (room <= 0) {
      setImageError('一次最多 3 张照片：全株、病部近景、健康对照各一张就够定位问题了。');
      return;
    }
    const chosen = files.slice(0, room);
    if (files.length > room) setImageError(`一次最多 3 张，已接收前 ${room} 张。`);
    setImageBusy(true);
    const added: AttachedImage[] = [];
    for (const file of chosen) {
      try {
        added.push(await uploadPhoto(file, { fieldId: activeField?.id, observedAt: dateToday() }));
      } catch (cause) {
        setImageError(
          cause instanceof Error ? cause.message : '图片上传失败，请重试。文字草稿不会丢。',
        );
      }
    }
    if (added.length) setImages((current) => [...current, ...added]);
    setImageBusy(false);
  }
  /** 粘贴图片：输入框内 Ctrl+V 直接贴图（截图后最常用） */
  function handlePaste(event: ReactClipboardEvent<HTMLTextAreaElement>) {
    const files = imageFilesFrom(event.clipboardData?.items);
    if (!files.length) return; // 纯文字粘贴交给浏览器默认行为
    event.preventDefault();
    void addPhotos(files);
  }
  const [clarifyAnswers, setClarifyAnswers] = useState<Record<string, ClarifyAnswers>>({});
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false);
  /** 生成中"插话"排队的下一条消息：同一条只排一条，流结束后自动发送。 */
  const queueRef = useRef<QueuedMessage | null>(null);
  const [queued, setQueued] = useState<string | null>(null);
  const [chatError, setChatError] = useState('');
  const [streamText, setStreamText] = useState('');
  const [streamStatus, setStreamStatus] = useState('');
  /** 一行思考提示：上一轮被 reset 掉的文字留一行预览，正文只在最终回答时出现 */
  const [thinkingLine, setThinkingLine] = useState('');
  const [includeWeather, setIncludeWeather] = useState(false);
  const [conversationBusy, setConversationBusy] = useState(false);
  const conversationBusyRef = useRef(false);
  const drafts = useRef<
    Record<string, { query: string; attachment: string | null; images?: AttachedImage[] }>
  >({});

  const [saveError, setSaveError] = useState('');

  const inputRef = useRef<HTMLTextAreaElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const scrollRef = useRef<HTMLDivElement>(null);
  const followScroll = useRef(true);

  const activeField = fields.find((f) => f.id === current.fieldId) ?? null;

  const lastMessage = current.messages.at(-1);
  /** 只有最新一张方案卡能加入任务：模型每轮都会重排一版方案，旧卡再点就会堆出重复待办 */
  const latestPlanId = [...current.messages].reverse().find((message) => message.plan)?.id;
  /** 生成过程只显示一行：优先当前轮的最后一个有内容的行，其次保留上一轮的一行预览 */
  const previewLine = lastLine(streamText) || thinkingLine;

  // Interrupted and partially completed answers need different wording: only the latter keeps tool cards.
  const incompleteHint =
    lastMessage?.error ||
    (lastMessage?.degraded
      ? '这条回答未完整生成，已保留的工具结果可以继续使用，也可以重试获得完整回答。'
      : '这条问题尚未收到完整回答，可重试。');

  useEffect(() => {
    // Follow the newest content instantly: CSS scroll-behavior:smooth would restart its animation on
    // every streaming delta and make the view lag behind. Switching conversations also jumps to the end.
    const el = scrollRef.current;
    if (!el || !followScroll.current) return;
    // Older engines (and the JSDOM test DOM) have no Element.scrollTo; scrollTop is the safe fallback.
    if (typeof el.scrollTo === 'function')
      el.scrollTo({ top: el.scrollHeight, behavior: 'instant' });
    else el.scrollTop = el.scrollHeight;
  }, [current.id, current.messages.length, sending, streamText, streamStatus]);
  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.style.height = 'auto';
      inputRef.current.style.height = `${Math.min(inputRef.current.scrollHeight, 180)}px`;
    }
  }, [query, view]);

  function fresh(fieldId: string | null = null) {
    if (conversationBusyRef.current) return;
    if (sendingRef.current || saveError) {
      setNotice(
        sendingRef.current
          ? '请先等待当前回答，或停止等待后再开启新对话。'
          : '请先保存当前回答，再开启新对话。',
      );
      return;
    }
    // 排队消息属于当前对话：换对话就作废，避免下一条问题被发到别的田块/会话里。
    clearQueue();
    drafts.current[current.id] = { query, attachment, images };
    setCurrent(newConversation(fieldId));
    setQuery('');
    setAttachment(null);
    setImages([]);
    setChatError('');
    setIncludeWeather(false);
    followScroll.current = true;
    onOpenChat();
  }
  function openConversation(conversation: Conversation) {
    if (sendingRef.current || saveError || conversationBusyRef.current) return;
    clearQueue();
    drafts.current[current.id] = { query, attachment, images };
    const draft = drafts.current[conversation.id];
    // 服务端不保存本地摘要字段，这里用浏览器里的副本补齐（服务端记录里没有摘要时为 null）。
    const storedSummary = readConversationSummary(conversation.id);
    setCurrent({
      ...conversation,
      ...(conversation.summary || !storedSummary
        ? {}
        : { summary: storedSummary.text, summarizedCount: storedSummary.count }),
    });
    setQuery(draft?.query || '');
    setAttachment(draft?.attachment || null);
    setImages(draft?.images ?? []);
    setChatError(conversation.messages.at(-1)?.error || '');
    setIncludeWeather(false);
    followScroll.current = true;
    onOpenChat();
  }
  async function renameConversation(id: string, title: string) {
    if (sendingRef.current || saveError || conversationBusyRef.current)
      throw new Error('请等待当前操作完成后再重命名。');
    conversationBusyRef.current = true;
    setConversationBusy(true);
    try {
      const saved = await api<Conversation>(`/conversations/${id}`, {
        method: 'PATCH',
        body: JSON.stringify({ title }),
      });
      setConversations((prev) => prev.map((c) => (c.id === id ? saved : c)));
      setCurrent((prev) => (prev.id === id ? { ...prev, title: saved.title } : prev));
      setNotice('对话标题已保存。');
    } finally {
      conversationBusyRef.current = false;
      setConversationBusy(false);
    }
  }
  async function deleteConversation(id: string) {
    if (sendingRef.current || saveError || conversationBusyRef.current)
      throw new Error('请等待当前操作完成后再删除。');
    conversationBusyRef.current = true;
    setConversationBusy(true);
    try {
      await api(`/conversations/${id}`, { method: 'DELETE' });
      setConversations((prev) => prev.filter((c) => c.id !== id));
      delete drafts.current[id];
      const removed = conversations.find((c) => c.id === id);
      setClarifyAnswers((prev) =>
        Object.fromEntries(
          Object.entries(prev).filter(([key]) => !removed?.messages.some((m) => m.id === key)),
        ),
      );
      if (current.id === id) {
        clearQueue();
        setCurrent(newConversation());
        setQuery('');
        setAttachment(null);
        setImages([]);
        setChatError('');
        setIncludeWeather(false);
      }
      clearConversationSummary(id);
      setNotice('对话已删除，无法撤销。田块与已创建的任务仍然保留。');
    } finally {
      conversationBusyRef.current = false;
      setConversationBusy(false);
    }
  }
  function suggest(prompt: string) {
    onOpenChat();
    setQuery(prompt);
    setTimeout(() => inputRef.current?.focus(), 0);
  }
  // 当前田块的田间照片档案：田块变化时刷新，用于"自动带最近几张"的提示与开关
  useEffect(() => {
    const fieldId = activeField?.id;
    if (!fieldId) {
      setFieldPhotos([]);
      return;
    }
    const controller = new AbortController();
    void api<{ photos: AttachedImage[] }>(`/uploads/field/${fieldId}`, {
      signal: controller.signal,
    })
      .then((result) => setFieldPhotos(Array.isArray(result.photos) ? result.photos : []))
      .catch(() => setFieldPhotos([]));
    return () => controller.abort();
  }, [activeField?.id, current.id]);

  /** 从田块页发起"看最近状况"：新开该田块的对话，并自动带上最近几张照片 */
  function analyzeField(fieldId: string) {
    const field = fields.find((item) => item.id === fieldId);
    clearQueue();
    drafts.current[current.id] = { query, attachment, images };
    setCurrent(newConversation(fieldId));
    setQuery('');
    setAttachment(null);
    setImages([]);
    setQuery(
      `看看${field?.name ?? '这块地'}最近的状况：和之前比，叶色、病斑和长势有哪些变化？接下来该怎么安排？`,
    );
    setAutoFieldPhotos(true);
    onOpenChat();
    setTimeout(() => inputRef.current?.focus(), 0);
  }
  // 当前模型是否按"能看图"处理：名单判定在服务端，这里只用来提前提示，不阻止用户先选图
  useEffect(() => {
    if (!settings.model) {
      setVisionReady(true);
      return;
    }
    const controller = new AbortController();
    void api<{ supported: boolean }>(
      `/chat/vision?model=${encodeURIComponent(settings.model)}&imageInput=${settings.vision ?? 'auto'}`,
      { signal: controller.signal },
    )
      .then((result) => setVisionReady(result.supported !== false))
      .catch(() => {
        /* 判定失败不阻塞发问，真正的拒绝由发送时的 415 明确给出 */
      });
    return () => controller.abort();
  }, [settings.model, settings.vision]);

  const saveChain = useRef<Promise<unknown>>(Promise.resolve());
  async function persist(conversation: Conversation) {
    // Serialise saves per conversation update order so a late stale response can never overwrite newer messages.
    const run = async () => {
      const saved = await api<Conversation>(`/conversations/${conversation.id}`, {
        method: 'PUT',
        body: JSON.stringify(conversation),
      });
      setConversations((prev) => [saved, ...prev.filter((c) => c.id !== saved.id)]);
      setSaveError('');
      return saved;
    };
    const next = saveChain.current.then(run, run);
    saveChain.current = next.catch(() => {});
    return next;
  }

  function clearQueue() {
    queueRef.current = null;
    setQueued(null);
  }
  function cancelQueued() {
    if (!queueRef.current) return;
    clearQueue();
    setNotice('已取消排队。刚写的内容不会再自动发送，需要时可以重新发送。');
  }
  /** 生成中按下发送：把这一条排队，同一条只排一条。 */
  function enqueue(text: string) {
    if (queueRef.current?.text === text) {
      setNotice(`这条内容已经排队：${queuePreview(text)}，本轮回答结束后会自动发送。`);
      return;
    }
    const replacing = !!queueRef.current;
    queueRef.current = {
      text,
      context: captureContext(activeField, includeWeather ? context : null),
      attachment,
      images,
    };
    setQueued(text);
    setQuery('');
    setAttachment(null);
    setImages([]);
    setChatError('');
    setNotice(
      replacing
        ? `已替换排队内容：${queuePreview(text)}，本轮回答结束后自动发送。`
        : `已排队：${queuePreview(text)}，本轮回答结束后自动发送。可在输入框上方取消排队。`,
    );
  }
  /** 流结束后自动发送排队消息；送不出去（未配置模型、保存失败等）时放回队列，等用户处理。 */
  function sendQueuedNext() {
    const pending = queueRef.current;
    if (!pending || sendingRef.current || conversationBusyRef.current) return;
    void send(false, undefined, pending).then((accepted) => {
      if (!accepted) return;
      if (queueRef.current === pending) clearQueue();
      // 排队消息已经发出，清掉"已排队…"的提示，避免用户以为还挂在队列里。
      setNotice('');
    });
  }

  async function send(
    retry = false,
    directReply?: string,
    queuedMessage?: QueuedMessage,
  ): Promise<boolean> {
    if (sendingRef.current || conversationBusyRef.current || !ready) {
      // 生成中的发送不是错误：排进队列，本轮回答结束后自动发出。
      const queuedText = (directReply ?? query).trim();
      if (sendingRef.current && !retry && queuedText) enqueue(queuedText);
      return false;
    }
    if (!settings.model || (settings.provider === 'custom' && !settings.apiKey)) {
      setSettingsOpen(true);
      return false;
    }
    if (saveError) {
      setChatError('请先重试保存当前对话，避免丢失已有内容。');
      return false;
    }
    const text = (queuedMessage?.text ?? directReply ?? query).trim();
    if (!retry && !text) return false;
    // 自动发送排队消息时运行在上一轮 send 的闭包里：必须取最新会话，否则会覆盖掉上一轮内容。
    const base = currentRef.current;
    // 长会话：早前消息先本地压缩成摘要，压缩后仍放不下才提示新建对话（服务端一次最多保存 500 条）。
    const compacted = compactConversation(base.messages, {
      summary: base.summary,
      summarizedCount: base.summarizedCount,
    });
    if (compacted.messages.length + (retry ? 0 : 2) > MAX_SAVED_MESSAGES) {
      setChatError('这段对话已经超出服务端保存上限，请新建对话继续；早前摘要已经保留可查。');
      return false;
    }
    const previous = retry ? retryMessages(compacted.messages) : compacted.messages;
    if (!previous) return false;
    // A card submission is its own message: do not overwrite or accidentally send the composer's draft/attachment.
    const withComposer = directReply === undefined && !retry;
    const sendContext =
      queuedMessage?.context ?? captureContext(activeField, includeWeather ? context : null);
    const sendImages = queuedMessage?.images ?? images;
    const sendAttachment = queuedMessage?.attachment ?? attachment;
    const messages = retry
      ? previous
      : [
          ...previous,
          {
            id: uid(),
            role: 'user' as const,
            content: text,
            requestContext: sendContext,
            ...(withComposer && sendAttachment ? { attachedData: sendAttachment } : {}),
            ...(withComposer && sendImages.length ? { images: sendImages } : {}),
          },
        ];
    const sentImageIds = retry ? [] : sendImages.map((image) => image.id);
    const requestContext = messages.at(-1)?.requestContext ?? captureContext(activeField, null);
    let history: ReturnType<typeof requestHistory>;
    try {
      history = requestHistory(messages, compacted.summary);
    } catch (e) {
      setChatError(errorText(e));
      return false;
    }
    const conversation = {
      ...base,
      title: base.messages.length ? base.title : text.slice(0, 36),
      messages,
      ...(compacted.summary
        ? { summary: compacted.summary, summarizedCount: compacted.summarizedCount }
        : {}),
    };
    const assistantId = uid();
    let partial = '';
    /** 只有完整回答并保存成功才算这一轮真正结束；失败时排队消息留在队列里等用户处理。 */
    let completedSend = false;
    const interrupted = (error: string): Conversation => ({
      ...conversation,
      messages: [
        ...messages,
        {
          id: assistantId,
          role: 'assistant',
          content: partial,
          status: 'interrupted',
          error,
        },
      ],
    });
    sendingRef.current = true;
    setSending(true);
    setChatError('');
    // 摘要随会话持久化：服务端记录里没有这个字段，所以同步存一份到浏览器（失败只影响刷新后查看摘要）。
    if (compacted.summary)
      saveConversationSummary(base.id, {
        summary: compacted.summary,
        summarizedCount: compacted.summarizedCount,
      });
    setStreamText('');
    setThinkingLine('');
    setStreamStatus('正在思考…');
    followScroll.current = true;
    const pending = interrupted('上次回答尚未完成，可重试这条问题。');
    setCurrent(pending);
    if (!retry && directReply === undefined) {
      setQuery('');
      setAttachment(null);
      setImages([]);
    }
    const controller = new AbortController();
    abortRef.current = controller;
    const timer = setTimeout(() => controller.abort('timeout'), 180000);
    try {
      await persist(pending);
      controller.signal.throwIfAborted();
      const result = await streamChat(
        {
          ...settings,
          messages: history,
          ...requestContext,
          imageIds: sentImageIds,
          imageInput: settings.vision ?? 'auto',
          autoFieldPhotos: autoFieldPhotos && !!activeField && fieldPhotos.length > 0,
        },
        controller.signal,
        ({ event, data }) => {
          if (controller.signal.aborted) return;
          // 工具轮会发 reset 清空正文；这里只保留"一行思考提示"，避免出现"文字出现又消失"的观感
          if (event === 'reset') {
            if (partial.trim()) setThinkingLine(lastLine(partial));
            partial = '';
            setStreamText('');
          }
          if (event === 'delta' && typeof data.text === 'string') {
            partial += data.text;
            setStreamText(partial);
            setStreamStatus('正在思考…');
          }
          if (event === 'status' && typeof data.text === 'string') setStreamStatus(data.text);
        },
      );
      controller.signal.throwIfAborted();
      clearTimeout(timer);
      const completed = {
        ...conversation,
        messages: [
          ...messages,
          {
            id: assistantId,
            role: 'assistant' as const,
            content: result.reply || '',
            plan: result.plan,
            risk: result.risk,
            clarify: result.clarify,
            sources: result.sources?.length ? result.sources : undefined,
            ...(result.degraded === true ? { degraded: true } : {}),
          },
        ],
      };
      setStreamText('');
      setStreamStatus('正在保存回答…');
      setCurrent(completed);
      try {
        await persist(completed);
        completedSend = true;
      } catch (e) {
        setSaveError(errorText(e));
      }
    } catch (e) {
      const error = controller.signal.aborted
        ? controller.signal.reason === 'timeout'
          ? '等待模型响应超时，可稍后重试。'
          : '已停止回答。供应商可能仍在处理刚才的请求。'
        : e instanceof TypeError
          ? '对话连接失败，请检查网络与 Java 服务后重试。'
          : errorText(e);
      setChatError(error);
      const failed = interrupted(error);
      setCurrent(failed);
      try {
        await persist(failed);
      } catch (saveFailure) {
        setSaveError(errorText(saveFailure));
      }
    } finally {
      clearTimeout(timer);
      sendingRef.current = false;
      setSending(false);
      abortRef.current = null;
      setStreamText('');
      setStreamStatus('');
      // 本轮结束（完成/失败/用户停止）后自动发出排队的下一条；失败时留在队列里等用户处理。
      if (completedSend && !retry && queuedMessage === undefined) sendQueuedNext();
    }
    return true;
  }

  function exportConversation() {
    // The export doubles as verification evidence: keep source cards and the honesty markers in it.
    const statusLine = (m: ChatMessage) =>
      m.status === 'interrupted'
        ? '\n\n> 状态：回答未完成（中断）\n'
        : m.degraded
          ? '\n\n> 状态：部分完成（工具结果已保留）\n'
          : '';
    const sourcesBlock = (m: ChatMessage) =>
      !m.sources?.length
        ? ''
        : `\n\n资料依据：\n` +
          m.sources
            .map((s) =>
              [
                `- 来源ID：${s.id}（${s.status === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}）`,
                `  标题：${s.title}`,
                s.institution ? `  机构：${s.institution}` : '',
                s.publishedAt ? `  发布日期：${s.publishedAt}` : '',
                s.region ? `  适用地区：${s.region}` : '',
                s.crop ? `  适用作物：${s.crop}` : '',
                s.growthStage ? `  生育期：${s.growthStage}` : '',
                s.url ? `  原文链接：${s.url}` : '  原文链接：无（未核验草稿）',
              ]
                .filter(Boolean)
                .join('\n'),
            )
            .join('\n');
    const content =
      `# ${current.title}\n\n农心 Agent 对话导出 · ${new Date().toLocaleString('zh-CN')}\n\n` +
      current.messages
        .map(
          (m) =>
            `## ${m.role === 'user' ? '我' : '农心'}\n\n${m.content}${statusLine(m)}${m.attachedData ? `\n\n农情数据：\n${m.attachedData}` : ''}${m.plan ? `\n\n农事建议（AI 生成，待核验）：\n${JSON.stringify(m.plan, null, 2)}` : ''}${m.risk?.result ? `\n\n${m.risk.result}` : ''}${m.clarify ? `\n\n待确认：\n${JSON.stringify(m.clarify, null, 2)}` : ''}${sourcesBlock(m)}`,
        )
        .join('\n\n---\n\n');
    const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `农心对话-${dateToday()}.md`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  useEffect(() => () => abortRef.current?.abort(), []);
  return {
    conversations,
    setConversations,
    current,
    setCurrent,
    query,
    setQuery,
    attachment,
    setAttachment,
    images,
    setImages,
    visionReady,
    fieldPhotos,
    autoFieldPhotos,
    setAutoFieldPhotos,
    imageError,
    imageBusy,
    addPhotos,
    handlePaste,
    clarifyAnswers,
    setClarifyAnswers,
    sending,
    sendingRef,
    /** 生成中排队等待自动发送的下一条消息（null 表示没有排队） */
    queued,
    cancelQueued,
    sendQueuedNext,
    chatError,
    setChatError,
    streamText,
    streamStatus,
    includeWeather,
    setIncludeWeather,
    conversationBusy,
    conversationBusyRef,
    drafts,
    saveError,
    setSaveError,
    inputRef,
    abortRef,
    scrollRef,
    followScroll,
    activeField,
    lastMessage,
    latestPlanId,
    previewLine,
    incompleteHint,
    fresh,
    openConversation,
    renameConversation,
    deleteConversation,
    suggest,
    analyzeField,
    persist,
    send,
    exportConversation,
  };
}
