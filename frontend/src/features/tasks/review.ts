import type { FieldProfile } from '@/features/fields/types';
import { localDate } from '@/shared/lib/date';
import { taskStatusLabel } from './status';
import type { FarmTask, TaskRecord } from './types';

/**
 * 复查之后的「调整」环节：把复查结论变成一条结构化追问草稿，并据此汇总《本季农事规划报告》。
 *
 * 这里只组织用户已经录入的内容：不推断结论、不补造缺失字段、不自动建任务、不改状态机。
 * 追问发出后仍走既有对话流程（助手按既有护栏给方案卡，用户自己点「加入任务」才登记）。
 */

/** 复查结论的 5 个结局：取值与后端 TaskRecord 一致，文案与任务页徽标一致。 */
export const REVIEW_OUTCOMES: Array<{ value: string; label: string }> = [
  { value: 'resolved', label: '问题已解决' },
  { value: 'improved', label: '明显好转' },
  { value: 'unchanged', label: '没有变化' },
  { value: 'worse', label: '反而变差' },
  { value: 'other', label: '其他情况' },
];
export const outcomeText = (outcome?: string | null) =>
  REVIEW_OUTCOMES.find((item) => item.value === outcome)?.label ?? '';

/** 前端只保留后端已登记的来源字段；旧版本只给 id 时，缺的字段一律不写。 */
export type TaskEvidenceCard = {
  id?: string;
  title?: string;
  url?: string;
  institution?: string;
  publishedAt?: string;
  region?: string;
  crop?: string;
  growthStage?: string;
  heading?: string;
  reviewStatus?: string;
  status?: string;
  excerpt?: string;
};

/** 后端 /api/tasks 返回的来源卡字段比前端声明的更全：这里按运行时形状安全读取。 */
const readCards = (task: FarmTask): TaskEvidenceCard[] =>
  Array.isArray(task.evidenceCards) ? (task.evidenceCards as TaskEvidenceCard[]) : [];

export const MISSING = '未记录';
export const MISSING_SOURCE = '来源信息以后端登记为准';

const clean = (value?: string | null) => (typeof value === 'string' ? value.trim() : '');
const orMissing = (value?: string | null) => clean(value) || MISSING;
const recordKindLabel = (kind: string) => (kind === 'review' ? '复查记录' : '执行记录');
/** 表格单元格里的换行会拆掉表格，这里统一压成一行。 */
const inline = (value: string) => value.replace(/\s+/g, ' ').trim();

/** 记录按日期升序（同日按创建时间），追问与报告引用的是同一份顺序。 */
const byDate = (a: TaskRecord, b: TaskRecord) =>
  (a.date || '').localeCompare(b.date || '') || (a.createdAt || '').localeCompare(b.createdAt || '');
export const executionRecords = (task: FarmTask) =>
  (task.records ?? []).filter((record) => record.kind !== 'review').sort(byDate);
export const reviewRecords = (task: FarmTask) =>
  (task.records ?? []).filter((record) => record.kind === 'review').sort(byDate);
export const latestReview = (task: FarmTask) => reviewRecords(task).at(-1) ?? null;

const recordLine = (record: TaskRecord) =>
  `${record.date || MISSING}：${clean(record.note) || MISSING}${
    outcomeText(record.outcome) ? `（${outcomeText(record.outcome)}）` : ''
  }`;

/** 每种复查结论对应一句具体的下一步诉求：5 个结局必须落到不同的动作上。 */
const NEXT_STEP_ASK: Record<string, string> = {
  worse:
    '这次比处理前更差，请评估是否需要调整或重新处理：先把可能的原因排清楚（是否判断错、时机不对、用量或方法不当、天气与田间条件影响），再给出要不要复配、改期或换措施的判断依据；',
  unchanged:
    '这次处理没有带来变化，请判断是否要换做法、还是再观察多久：如果需要继续观察，请给出观察多久、观察哪些指标、到什么情况就必须换做法；',
  resolved:
    '这次的问题已经解决，请排下一阶段的常规管理：接下来该做哪些事、按什么生育期或时间窗口安排、哪些指标需要继续盯着；',
  improved:
    '情况已经明显好转，请判断是否还需要补一次处理，还是直接转入常规管理：并说明判断依据与转入常规管理后要盯的指标；',
  other:
    '这次复查的情况不在上面几类里，请根据实际记录判断下一步：需要补充哪些信息、接下来该做什么、什么时候做；',
};

/** 点击「据本次复查排下一步」时组装的结构化追问：田块与作物、原任务、执行记录、复查结论、期望产出。 */
export function buildReviewFollowUpQuestion(
  task: FarmTask,
  field?: FieldProfile | null,
  today = localDate(),
): string {
  const fieldName = clean(field?.name) || clean(task.fieldName) || '未关联田块';
  const crop = clean(field?.crop);
  const executions = executionRecords(task);
  const reviews = reviewRecords(task);
  const latest = reviews.at(-1);
  const outcome = outcomeText(latest?.outcome);
  const ask =
    NEXT_STEP_ASK[clean(latest?.outcome)] ??
    `这次复查结论${
      outcome ? `为「${outcome}」` : '还没有勾选'
    }，请先判断它对下一步意味着什么：需要补充哪些信息、下一步做什么、什么时候做；`;
  return [
    '# 据本次复查排下一步',
    '',
    `- 田块与作物：${fieldName}${crop ? `（${crop}）` : '（作物未记录）'}`,
    `- 原任务：${clean(task.title) || MISSING}`,
    `- 原任务时间窗口：${orMissing(task.timeWindow)}`,
    `- 本次执行记录：${
      executions.length
        ? executions
            .map((record) => recordLine(record).replace(/（[^）]*）$/, ''))
            .join('；')
        : '没有录入执行记录（该结果未经核验）'
    }`,
    `- 复查结论：${outcome || '未勾选'}`,
    `- 复查日期：${clean(latest?.date) || MISSING}`,
    `- 复查记录：${latest ? recordLine(latest) : '没有录入复查记录'}`,
    '',
    '本轮希望得到的产出：给出下一步该做什么。',
    ask,
    '',
    '要求：给出的下一步要能直接落到农事任务上（做什么、时间窗口、执行条件、所需物料、复查要点）；依据不足或条件不合适时，请直说不能做以及需要先确认什么，不要给没有依据的用药建议。',
    `方案请按「${fieldName}」安排；加入任务时会关联当前对话所选田块，请在任务页核对。`,
    `今天是 ${today}。`,
  ].join('\n');
}

/** 报告下载：沿用对话导出的写法（Blob + 临时链接触发下载），不引入新依赖。 */
export function downloadMarkdownFile(filename: string, content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** 文件名里的田块名不能带路径分隔符与非法字符，其余保留用户原文。 */
export const reportFilename = (fieldName: string, date = localDate()) =>
  `农心-农事规划报告-${inline(fieldName).replace(/[\\/:*?"<>|]/g, '-') || '未命名田块'}-${date}.md`;

/** 计划总览排序：有日期的在前按日期升序，日期待定的排最后，同日按创建时间。 */
const byPlanOrder = (a: FarmTask, b: FarmTask) =>
  (a.date || '9999-12-31').localeCompare(b.date || '9999-12-31') ||
  (a.createdAt || '').localeCompare(b.createdAt || '');

function overviewTable(sorted: FarmTask[]): string[] {
  if (!sorted.length) return ['这块田还没有农事任务记录。'];
  return [
    '| 日期 | 时间窗口 | 任务 | 状态 | 执行条件 | 方法与用量 | 所需物料 | 复查要点 | 风险与禁忌 | 依据来源ID |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ...sorted.map((task) =>
      `| ${[
        clean(task.date) || '待定',
        orMissing(task.timeWindow),
        clean(task.title) || MISSING,
        taskStatusLabel(task),
        orMissing(task.condition),
        orMissing(task.method),
        orMissing(task.materials),
        orMissing(task.review),
        orMissing(task.risk),
        (task.evidence ?? []).join('、') || MISSING,
      ]
        .map((cell) => inline(cell).replace(/\|/g, '\\|'))
        .join(' | ')} |`,
    ),
  ];
}

function recordsSection(sorted: FarmTask[]): string[] {
  const withRecords = sorted.filter((task) => (task.records ?? []).length > 0);
  if (!withRecords.length) return ['还没有提交任何执行或复查记录。'];
  const out: string[] = [];
  for (const task of withRecords) {
    out.push(
      `### ${clean(task.title) || MISSING}（${clean(task.date) ? `${task.date} · ` : '日期待定 · '}${taskStatusLabel(task)}）`,
      '',
    );
    for (const record of [...(task.records ?? [])].sort(byDate))
      out.push(`- ${recordKindLabel(record.kind)} ${recordLine(record)}`);
    out.push('');
  }
  return out;
}

function evidenceSection(sorted: FarmTask[]): string[] {
  const out: string[] = [];
  for (const task of sorted) {
    const ids = (task.evidence ?? []).filter((id) => clean(id));
    const cards = readCards(task);
    if (!ids.length && !cards.length) continue;
    out.push(`- ${clean(task.title) || MISSING}`, `  - 来源ID：${ids.join('、') || MISSING}`);
    if (!cards.length) {
      out.push(`  - ${MISSING_SOURCE}（本机未取到来源卡信息）。`);
      continue;
    }
    for (const card of cards) {
      const facts = [
        `标题：${clean(card.title) || MISSING}`,
        clean(card.institution) ? `机构：${card.institution}` : '',
        clean(card.publishedAt) ? `发布：${card.publishedAt}` : '',
        clean(card.region) ? `适用地区：${card.region}` : '',
        clean(card.crop) ? `适用作物：${card.crop}` : '',
        clean(card.growthStage) ? `生育期：${card.growthStage}` : '',
        `核验状态：${
          card.status === 'verified' || card.reviewStatus === 'verified'
            ? '已核验原文'
            : '未核验草稿'
        }`,
        clean(card.url) ? `原文：${card.url}` : '原文：无链接',
      ].filter(Boolean);
      out.push(`  - 来源卡（${clean(card.id) || MISSING}）：${facts.join('；')}`);
    }
  }
  if (!out.length) out.push('这些任务还没有附任何依据来源ID。');
  return out;
}

/** 风险与注意：只列真实缺口，不写"未发现问题"这类无据结论。 */
function riskSection(field: FieldProfile | undefined, tasks: FarmTask[], sorted: FarmTask[]): string[] {
  const lines: string[] = [];
  const missing: string[] = [];
  if (!clean(field?.crop)) missing.push('作物');
  if (!clean(field?.variety)) missing.push('品种');
  if (!clean(field?.sowDate)) missing.push('播期');
  if (field?.areaMu === undefined || field?.areaMu === null) missing.push('面积');
  if (missing.length) lines.push(`- 田块档案未记录：${missing.join('、')}。`);
  if (!tasks.length) lines.push('- 这块田还没有任何农事任务，报告不含计划与执行内容。');

  const planned = sorted.filter((task) => task.status !== 'cancelled');
  const undated = planned.filter((task) => !clean(task.date)).length;
  if (undated) lines.push(`- ${undated} 项任务日期待定，执行时间以现场判断为准。`);

  for (const gap of [
    { label: '执行条件', value: (task: FarmTask) => task.condition },
    { label: '方法/用量', value: (task: FarmTask) => task.method },
    { label: '所需物料', value: (task: FarmTask) => task.materials },
    { label: '复查要点', value: (task: FarmTask) => task.review },
    { label: '风险与禁忌', value: (task: FarmTask) => task.risk },
  ]) {
    const gaps = planned.filter((task) => !clean(gap.value(task)));
    if (gaps.length)
      lines.push(
        `- ${gap.label}未记录（${gaps.length} 项）：${gaps.map((task) => task.title).join('、')}。`,
      );
  }

  const pending = sorted.filter((task) => task.status === 'pending_confirmation');
  if (pending.length)
    lines.push(
      `- 待确认任务 ${pending.length} 项（确认后才进入执行）：${pending
        .map((task) => task.title)
        .join('、')}。`,
    );
  const noRecord = planned.filter(
    (task) => task.status !== 'pending_confirmation' && !(task.records ?? []).length,
  );
  if (noRecord.length)
    lines.push(
      `- 还没有提交任何记录的任务 ${noRecord.length} 项：${noRecord
        .map((task) => task.title)
        .join('、')}。`,
    );
  const noOutcome = sorted.filter(
    (task) =>
      reviewRecords(task).length > 0 &&
      reviewRecords(task).every((record) => !outcomeText(record.outcome)),
  );
  if (noOutcome.length)
    lines.push(
      `- 复查结论未勾选：${noOutcome.map((task) => task.title).join('、')}（无法据此判断下一步）。`,
    );
  if (planned.some((task) => clean(task.materials)) || planned.some((task) => clean(task.method)))
    lines.push('- 物料与用量建议须核对当地登记标签，并以现场条件为准。');
  if (!lines.length) lines.push('- 未发现需要特别提示的缺口。');
  return lines;
}

/** 报告文件名与页脚用的是本机日期（本地时区），与任务页的日期口径一致。 */
const reportDate = (at: Date) =>
  `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, '0')}-${String(
    at.getDate(),
  ).padStart(2, '0')}`;

/** 《本季农事规划报告》：只用系统里真实存在的数据，缺失字段一律写「未记录/待确认」。 */
export function buildSeasonPlanReport(
  field: FieldProfile | null | undefined,
  tasks: FarmTask[],
  generatedAt = new Date(),
): string {
  const today = reportDate(generatedAt);
  const name = clean(field?.name) || '未关联田块';
  const sorted = [...tasks].sort(byPlanOrder);
  const generated = `${today} ${String(generatedAt.getHours()).padStart(2, '0')}:${String(
    generatedAt.getMinutes(),
  ).padStart(2, '0')}`;
  const sections = [
    [
      `# 本季农事规划报告 · ${name}`,
      '',
      `田块：${name}${clean(field?.crop) ? `　作物：${field?.crop}` : ''}`,
      `生成时间：${generated}`,
      '本报告由农心 Agent 依据本机登记的田块档案、农事任务与执行/复查记录汇总生成，只包含已录入的数据。',
      '',
      '## 一、田块档案',
      '',
      '| 项目 | 内容 |',
      '| --- | --- |',
      `| 田块名称 | ${name} |`,
      `| 作物 | ${orMissing(field?.crop)} |`,
      `| 品种 | ${orMissing(field?.variety)} |`,
      `| 播期 | ${orMissing(field?.sowDate)} |`,
      `| 面积 | ${
        field?.areaMu === undefined || field?.areaMu === null ? MISSING : `${field.areaMu} 亩`
      } |`,
      `| 备注 | ${orMissing(field?.notes)} |`,
    ],
    [
      '## 二、计划总览',
      '',
      `共 ${sorted.length} 项任务：已完成 ${
        sorted.filter((task) => task.status === 'completed').length
      } 项，取消 ${sorted.filter((task) => task.status === 'cancelled').length} 项，其余 ${
        sorted.filter((task) => task.status !== 'completed' && task.status !== 'cancelled').length
      } 项仍在流程中。`,
      '',
      ...overviewTable(sorted),
    ],
    ['## 三、执行与复查记录', '', ...recordsSection(sorted)],
    ['## 四、资料依据', '', ...evidenceSection(sorted)],
    ['## 五、风险与注意', '', ...riskSection(field ?? undefined, tasks, sorted)],
    [
      '---',
      '',
      `本报告生成时间：${generated}；数据截至生成时刻，之后新增的记录不在此文件内。`,
      '报告中的方案只供用户审阅，不代表已执行；执行状态以任务页的记录为准，「已完成」只由提交的执行与复查记录推进。',
      '农药使用须以当地登记的标签为准，并遵守安全间隔期；生成结果不能替代现场判断。',
    ],
  ];
  return `${sections.map((section) => section.join('\n')).join('\n\n')}\n`;
}
