import type { AiSettings } from './types';

export const PROVIDERS = [
  {
    id: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-flash',
  },
  { id: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  {
    id: 'siliconflow',
    label: '硅基流动',
    baseUrl: 'https://api.siliconflow.cn/v1',
    model: 'deepseek-ai/DeepSeek-V3.2',
  },
  { id: 'custom', label: '自定义兼容接口', baseUrl: '', model: '' },
];
export const DEFAULT_SETTINGS: AiSettings = {
  provider: 'deepseek',
  model: 'deepseek-flash',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
};
export const SETTINGS_KEY = 'nongxin-ai-settings';

function parseSettings(raw: string | null): AiSettings | null {
  try {
    const value = JSON.parse(raw || 'null');
    if (
      value &&
      PROVIDERS.some((p) => p.id === value.provider) &&
      ['model', 'baseUrl', 'apiKey'].every((k) => typeof value[k] === 'string')
    )
      return {
        provider: value.provider,
        model: value.model,
        baseUrl: value.baseUrl,
        apiKey: value.apiKey,
        ...(['auto', 'on', 'off'].includes(value.vision) ? { vision: value.vision } : {}),
      };
  } catch {
    /* Invalid saved data must not prevent opening settings. */
  }
  return null;
}

export function writeSettings(value: AiSettings): void {
  try {
    const serialized = JSON.stringify(value);
    localStorage.setItem(SETTINGS_KEY, serialized);
    if (localStorage.getItem(SETTINGS_KEY) !== serialized)
      throw new Error('Storage did not retain settings');
  } catch {
    // Never include storage exception text: it may contain the serialized credential.
    throw new Error('浏览器不允许保存模型设置，请开启此网站的本地存储权限后重试。');
  }
  try {
    sessionStorage.removeItem(SETTINGS_KEY);
  } catch {
    /* The persistent record takes precedence, including an explicitly cleared key. */
  }
}

export function readSettings(): AiSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    // Never resurrect an old session key when a persistent record exists but is damaged.
    if (raw !== null) return parseSettings(raw) ?? { ...DEFAULT_SETTINGS };
  } catch {
    /* Legacy session settings can still be used if persistent storage is blocked. */
  }
  try {
    const legacy = parseSettings(sessionStorage.getItem(SETTINGS_KEY));
    if (legacy) {
      try {
        writeSettings(legacy);
      } catch {
        /* Keep the legacy copy when migration cannot be persisted. */
      }
      return legacy;
    }
  } catch {
    /* Storage may be unavailable in restricted contexts. */
  }
  return { ...DEFAULT_SETTINGS };
}
