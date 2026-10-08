import type { AiSettings } from './types';

/** Shared by automatic persistence, explicit save and connection testing. */
export function normalizeSettings(draft: AiSettings): AiSettings {
  if (!draft.model.trim()) throw new Error('请填写模型名称。');
  if (draft.provider === 'custom' && !draft.apiKey.trim())
    throw new Error('自定义供应商请填写 API Key。');
  if (draft.apiKey.trim() && draft.apiKey.trim().length < 12)
    throw new Error('API Key 至少需要 12 个字符；过短的密钥不会被后端作为自有密钥使用。');
  if (draft.provider === 'custom') {
    let url: URL;
    try {
      url = new URL(draft.baseUrl);
    } catch {
      throw new Error('请填写完整的 HTTPS API 地址。');
    }
    if (
      url.protocol !== 'https:' ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      (url.port && url.port !== '443')
    )
      throw new Error('API 地址须为公开的 HTTPS 地址，不能包含账号、参数或片段，端口只能是 443。');
  }
  return {
    ...draft,
    apiKey: draft.apiKey.trim(),
    model: draft.model.trim(),
    baseUrl: draft.baseUrl.trim(),
  };
}
