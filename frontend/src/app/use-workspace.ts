import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorText, uid } from '@/shared/api/client';
import { localDate as dateToday } from '@/shared/lib/date';
import { readSettings, writeSettings } from '@/features/settings/storage';
import type { AiSettings } from '@/features/settings/types';
import type { FieldProfile } from '@/features/fields/types';
import type { FarmTask } from '@/features/tasks/types';
import { buildReviewFollowUpQuestion } from '@/features/tasks/review';
import type { KnowledgeSource } from '@/features/knowledge/types';
import type { ChatMessage, Conversation } from '@/features/chat/types';
import { useChatWorkspace } from '@/features/chat/use-chat-workspace';
import { useLiveContext } from '@/features/context/use-live-context';
import {
  usePanelWidth,
  useMediaQuery,
  SIDEBAR_KEY,
  SIDEBAR_DEFAULT,
  SIDEBAR_MIN,
  SIDEBAR_MAX,
  RAIL_KEY,
  RAIL_DEFAULT,
  RAIL_MIN,
  RAIL_MAX,
} from '@/shared/hooks/layout';
import type { View } from './navigation';

export function useWorkspace() {
  const [view, setView] = useState<View>('chat');
  const [mobileNav, setMobileNav] = useState(false);
  const [fields, setFields] = useState<FieldProfile[]>([]);
  const [tasks, setTasks] = useState<FarmTask[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeSource[]>([]);
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [settings, setSettings] = useState<AiSettings>(readSettings);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [wechatOpen, setWechatOpen] = useState(false);
  const [notice, setNotice] = useState('');
  const [showArt, setShowArt] = useState(true);
  const [planTarget, setPlanTarget] = useState<ChatMessage | null>(null);
  const [planBusy, setPlanBusy] = useState(false);
  const planSaving = useRef(false);
  const [planError, setPlanError] = useState('');
  const [scheduleQueue, setScheduleQueue] = useState<FarmTask[]>([]);
  const sidebar = usePanelWidth(SIDEBAR_KEY, SIDEBAR_DEFAULT, SIDEBAR_MIN, SIDEBAR_MAX, 1);
  const rail = usePanelWidth(RAIL_KEY, RAIL_DEFAULT, RAIL_MIN, RAIL_MAX, -1);
  // 窄屏（≤1000px）时右侧栏整栏隐藏，天气模块必须回到左侧栏，否则手机上完全没有天气入口
  const railVisible = useMediaQuery('(min-width: 1001px)');
  const go = (next: View) => {
    setView(next);
    setMobileNav(false);
  };
  const live = useLiveContext(() => chat.setIncludeWeather(false));
  const chat = useChatWorkspace({
    fields,
    settings,
    context: live.context,
    ready,
    view,
    onOpenChat: () => go('chat'),
    setNotice,
    setSettingsOpen: changeSettingsOpen,
  });
  const {
    conversations,
    setConversations,
    current,
    setCurrent,
    setQuery,
    sendingRef,
    inputRef,
    activeField,
  } = chat;
  const pendingTasks = tasks.filter((t) =>
    ['pending_confirmation', 'pending', 'awaiting_review'].includes(t.status),
  );
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({});
  // 最近对话按田块分组：组顺序取"最近有对话的田块在前"，组内保持服务端的时间倒序
  const historyGroups = (() => {
    const groups: Array<{ key: string; label: string; hint: string; items: Conversation[] }> = [];
    const index = new Map<string, number>();
    for (const conversation of conversations) {
      const key = conversation.fieldId ?? '__none__';
      let at = index.get(key);
      if (at === undefined) {
        at = groups.length;
        index.set(key, at);
        const field = fields.find((f) => f.id === conversation.fieldId);
        groups.push({
          key,
          label: field ? field.name : '未关联田块',
          hint: field ? field.crop : '通用咨询',
          items: [],
        });
      }
      groups[at].items.push(conversation);
    }
    return groups;
  })();
  const loadWorkspace = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [health, f, t, c, k] = await Promise.all([
        api<{ status: string }>('/health'),
        api<FieldProfile[]>('/fields'),
        api<FarmTask[]>('/tasks'),
        api<Conversation[]>('/conversations'),
        api<KnowledgeSource[]>('/knowledge'),
      ]);
      if (health.status !== 'ok' || ![f, t, c, k].every(Array.isArray))
        throw new Error('后端接口格式不正确，未载入工作区。');
      setFields(f);
      setTasks(t);
      setConversations(c);
      setKnowledge(k);
      setReady(true);
    } catch (e) {
      setLoadError(errorText(e));
      setReady(false);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void loadWorkspace();
  }, [loadWorkspace]);
  useEffect(() => {
    if (notice) {
      const timer = setTimeout(() => setNotice(''), 5500);
      return () => clearTimeout(timer);
    }
  }, [notice]);

  function applySettings(value: AiSettings) {
    rememberSettings(value);
    setSettingsOpen(false);
    setNotice('模型设置已保存在当前浏览器，重启后可继续使用。');
  }
  function rememberSettings(value: AiSettings) {
    writeSettings(value);
    setSettings(value);
  }
  function changeSettingsOpen(open: boolean) {
    if (open) setSettings(readSettings());
    setSettingsOpen(open);
  }
  async function saveField(field: FieldProfile) {
    if (sendingRef.current && current.fieldId === field.id)
      throw new Error('当前正在使用这个田块进行对话，请等待回答后再编辑。');
    const exists = fields.some((f) => f.id === field.id);
    const saved = await api<FieldProfile>(exists ? `/fields/${field.id}` : '/fields', {
      method: exists ? 'PUT' : 'POST',
      body: JSON.stringify(field),
    });
    setFields((prev) => [saved, ...prev.filter((f) => f.id !== saved.id)]);
    return saved;
  }
  async function removeField(id: string) {
    if (sendingRef.current && current.fieldId === id)
      throw new Error('当前正在使用这个田块进行对话，请等待回答后再删除。');
    await api(`/fields/${id}`, { method: 'DELETE' });
    setFields((prev) => prev.filter((f) => f.id !== id));
    if (current.fieldId === id) setCurrent((prev) => ({ ...prev, fieldId: null }));
    setConversations((prev) => prev.map((c) => (c.fieldId === id ? { ...c, fieldId: null } : c)));
    setTasks((prev) => prev.map((t) => (t.fieldId === id ? { ...t, fieldId: null } : t)));
  }
  async function addRecord(id: string, note: string) {
    const saved = await api<FieldProfile>(`/fields/${id}/records`, {
      method: 'POST',
      body: JSON.stringify({ date: dateToday(), note }),
    });
    setFields((prev) => prev.map((f) => (f.id === id ? saved : f)));
  }
  async function saveTask(task: FarmTask) {
    const exists = tasks.some((t) => t.id === task.id);
    const saved = await api<FarmTask>(exists ? `/tasks/${task.id}` : '/tasks', {
      method: exists ? 'PUT' : 'POST',
      body: JSON.stringify(task),
    });
    setTasks((prev) => [saved, ...prev.filter((t) => t.id !== saved.id)]);
    return saved;
  }
  async function removeTask(id: string) {
    await api(`/tasks/${id}`, { method: 'DELETE' });
    setTasks((prev) => prev.filter((t) => t.id !== id));
  }
  async function changeTaskStatus(id: string, status: string) {
    const saved = await api<FarmTask>(`/tasks/${id}/status`, {
      method: 'POST',
      body: JSON.stringify({ status }),
    });
    setTasks((prev) => prev.map((t) => (t.id === saved.id ? saved : t)));
    return saved;
  }
  async function addTaskRecord(
    id: string,
    record: { kind: string; date: string; note: string; outcome?: string },
  ) {
    const saved = await api<FarmTask>(`/tasks/${id}/records`, {
      method: 'POST',
      body: JSON.stringify(record),
    });
    setTasks((prev) => prev.map((t) => (t.id === saved.id ? saved : t)));
    return saved;
  }
  /** 把任务与用户记录显式写进提问草稿：回到对话讨论时，模型看到的是"你做了什么"，而不是把建议当成已执行。 */
  function discussTask(task: FarmTask) {
    const records = task.records ?? [];
    const lines = [
      `关于任务「${task.title}」（当前状态：${task.statusLabel || task.status}${task.fieldName ? `，田块：${task.fieldName}` : ''}${task.date ? `，计划日期：${task.date}` : ''}）想继续讨论：`,
      task.condition ? `- 原定执行条件：${task.condition}` : '',
      task.review ? `- 原定复查要点：${task.review}` : '',
      ...records.map(
        (record) =>
          `- ${record.kind === 'review' ? '复查记录' : '执行记录'}（${record.date}）：${record.note}${record.outcome ? `，结论：${record.outcome}` : ''}`,
      ),
      records.length === 0 ? '- 目前还没有提交任何执行记录。' : '',
      '',
      '我的问题是：',
    ].filter((line) => line !== '');
    go('chat');
    setQuery(lines.join('\n'));
    setTimeout(() => inputRef.current?.focus(), 0);
  }
  /**
   * 闭环补上「调整」：已完成任务点「据本次复查排下一步」时，把复查结论变成一条结构化追问放进对话草稿。
   * 这里只负责组装与切页；方案卡仍由助手按既有护栏给出，用户自己点「加入任务」才登记（沿用 addPlan 的幂等）。
   */
  function reviewFollowUp(task: FarmTask, question?: string) {
    go('chat');
    setQuery(
      question ??
        buildReviewFollowUpQuestion(
          task,
          fields.find((f) => f.id === task.fieldId),
        ),
    );
    setTimeout(() => inputRef.current?.focus(), 0);
  }
  async function addPlan() {
    if (!planTarget?.plan?.items || planSaving.current) return;
    planSaving.current = true;
    setPlanBusy(true);
    setPlanError('');
    let count = 0,
      reused = 0;
    const registered: FarmTask[] = [];
    try {
      for (const [index, item] of planTarget.plan.items.entries()) {
        const planItemId = item.itemId || `p${index + 1}`;
        const already = tasks.find(
          (t) => t.sourceMessageId === planTarget.id && t.planItemId === planItemId,
        );
        if (already) {
          reused++;
          if (already.status === 'pending_confirmation') registered.push(already);
          continue;
        }
        const date = item.date && /^\d{4}-\d{2}-\d{2}$/.test(item.date) ? item.date : '';
        const planned = {
          id: uid(),
          title: item.task,
          date,
          fieldId: activeField?.id ?? null,
          fieldName: activeField?.name ?? '',
          status: 'pending_confirmation',
          timeWindow: item.window || (!date && item.date ? `建议时间：${item.date}` : ''),
          materials: item.materials || '',
          risk: item.warning || '',
          evidence: (item.evidence ?? []).filter(
            (id): id is string => typeof id === 'string' && !!id.trim(),
          ),
          planItemId,
          condition: item.condition || '',
          method: [item.method, item.dosage ? `用量建议（须核验标签）：${item.dosage}` : '']
            .filter(Boolean)
            .join('\n'),
          review: item.review || '',
          note: '来自 AI 农事建议，确认时间与条件后再执行。',
          createdAt: new Date().toISOString(),
          sourceMessageId: planTarget.id,
        };
        const saved = await saveTask(planned);
        if (saved.status === 'pending_confirmation') registered.push(saved);
        // 服务端会做两层幂等：命中的是"已有任务"时返回的 id 与我提交的不同，据此区分新增与复用
        if (saved.id === planned.id) count++;
        else reused++;
      }
      setPlanTarget(null);
      if (registered.length > 0) setScheduleQueue(registered);
      setNotice(
        count
          ? `已登记 ${count} 项任务（待确认），可在「农事任务」里确认安排、执行并提交记录。`
          : `这些方案项${reused ? `（${reused} 项）` : ''}已经在待办里了，没有重复添加。`,
      );
    } catch (e) {
      setPlanError(
        `${count ? `已登记 ${count} 项，其余未保存。` : ''}${errorText(e)} 再次提交会跳过已登记的项目。`,
      );
    } finally {
      planSaving.current = false;
      setPlanBusy(false);
    }
  }

  return {
    ...chat,
    ...live,
    view,
    setMobileNav,
    mobileNav,
    fields,
    tasks,
    knowledge,
    ready,
    loading,
    loadError,
    settings,
    settingsOpen,
    setSettingsOpen: changeSettingsOpen,
    mobileOpen,
    setMobileOpen,
    wechatOpen,
    setWechatOpen,
    notice,
    setNotice,
    showArt,
    setShowArt,
    planTarget,
    setPlanTarget,
    planBusy,
    planError,
    setPlanError,
    scheduleQueue,
    setScheduleQueue,
    sidebar,
    rail,
    railVisible,
    pendingTasks,
    collapsedGroups,
    setCollapsedGroups,
    historyGroups,
    loadWorkspace,
    go,
    applySettings,
    rememberSettings,
    saveField,
    removeField,
    addRecord,
    saveTask,
    removeTask,
    changeTaskStatus,
    addTaskRecord,
    discussTask,
    reviewFollowUp,
    addPlan,
  };
}

export type WorkspaceModel = ReturnType<typeof useWorkspace>;
