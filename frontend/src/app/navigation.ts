import { MessageSquare, Sprout, ClipboardList, BookOpen, Leaf, Tractor } from 'lucide-react';

export type View = 'chat' | 'fields' | 'tasks' | 'knowledge';
export const NAV = [
  { id: 'chat' as View, label: '问农心', icon: MessageSquare },
  { id: 'fields' as View, label: '我的田块', icon: Sprout },
  { id: 'tasks' as View, label: '农事任务', icon: ClipboardList },
  { id: 'knowledge' as View, label: '农技资料', icon: BookOpen },
];
export const STARTERS = [
  {
    icon: Leaf,
    name: '一起排查问题',
    detail: '从症状出发，找到下一步',
    prompt: '我想排查作物叶片出现的问题，应该先观察和记录哪些信息？',
  },
  {
    icon: ClipboardList,
    name: '安排接下来的农事',
    detail: '把建议变成可执行的任务',
    prompt: '我想制定接下来一周的农事计划。需要向你提供哪些田块信息？',
  },
  {
    icon: Tractor,
    name: '读懂农情数据',
    detail: '附上实测记录，寻找线索',
    prompt: '我准备提供农情数据，请帮我分析，并说明判断依据和缺失的信息。',
  },
];
