/** 对话中的来源卡：只包含后端资料库真实存在的片段。 */
export type KnowledgeSourceCard = {
  id: string;
  title: string;
  institution?: string;
  url?: string;
  publishedAt?: string;
  region?: string;
  crop?: string;
  growthStage?: string;
  heading?: string;
  status: 'verified' | 'unverified';
  excerpt?: string;
};

/** 来源登记条目（/api/knowledge）。 */
export type KnowledgeSource = {
  id: string;
  title: string;
  institution?: string;
  url?: string;
  publishedAt?: string;
  fetchedAt?: string;
  region?: string;
  crops?: string[];
  topic?: string;
  version?: string;
  license?: string;
  reviewStatus: 'verified' | 'unverified';
  reviewNote?: string;
  chunks?: Array<{
    id: string;
    heading?: string;
    locator?: string;
    crop?: string;
    region?: string;
    growthStage?: string;
    text?: string;
  }>;
};
