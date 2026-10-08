import type { FieldProfile } from '@/features/fields/types';
import type { AttachedImage } from '@/features/images/types';
import type { KnowledgeSourceCard } from '@/features/knowledge/types';
import type { LiveContext } from '@/features/context/types';

export type PlanItem = {
  /** 服务端为每个方案项注入的稳定 ID，用于「加入任务」的幂等登记 */
  itemId?: string;
  date?: string;
  /** 建议时间窗口 / 物候窗口，缺失时为「待确认」 */
  window?: string;
  task: string;
  /** 所需物料，缺失时为「待确认」 */
  materials?: string;
  dosage?: string;
  method?: string;
  condition?: string;
  warning?: string;
  review?: string;
  evidence?: string[];
};

export type PlanArgs = {
  title?: string;
  crop?: string;
  fieldName?: string;
  summary?: string;
  items?: PlanItem[];
};

export type RiskArgs = {
  data?: string;
  result?: string; // 工具执行输出（服务端 riskReportText）
};

export type ClarifyItem = {
  question?: string;
  options?: string[];
  hint?: string;
};

export type ClarifyArgs = {
  intro?: string;
  items?: ClarifyItem[];
};

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  attachedData?: string;
  images?: AttachedImage[];
  plan?: PlanArgs | null;
  risk?: RiskArgs | null;
  clarify?: ClarifyArgs | null;
  evidence?: Array<{ id: string; title: string; crop: string; source: string }>;
  /** 本次回答实际检索命中的资料依据（后端生成，模型无法伪造）。 */
  sources?: KnowledgeSourceCard[];
  status?: 'interrupted';
  /** 工具产出已保留但回答未完整生成（供应商在工具轮后失败或超时）；可重试，不进入后续对话历史。 */
  degraded?: boolean;
  error?: string;
  requestContext?: ChatContext;
};

export type ChatContext = {
  field: FieldProfile | null;
  location: {
    label: string | null;
    latitude: number;
    longitude: number;
    method: LiveContext['method'];
  } | null;
  weather: (LiveContext & { locationText: string }) | null;
};

export type Conversation = {
  id: string;
  title: string;
  fieldId: string | null;
  messages: ChatMessage[];
  createdAt: string;
  /** 本地抽取式摘要：早前消息滚出上下文后的压缩结果，随请求历史开头一起发送（不进入服务端契约）。 */
  summary?: string;
  /** 摘要已经覆盖的早前消息条数（含逐次压缩的累计值），用于界面提示"已自动压缩 N 条"。 */
  summarizedCount?: number;
};
