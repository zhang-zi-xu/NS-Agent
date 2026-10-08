import type { LiveContext } from './types';

export function normalizeContext(
  raw: Partial<LiveContext>,
  method: 'device' | 'manual',
  accuracy = 0,
): LiveContext {
  return {
    ...raw,
    method,
    latitude: raw.latitude ?? 0,
    longitude: raw.longitude ?? 0,
    accuracy,
    locatedAt: Date.now(),
    location: raw.location ?? null,
    timezone: raw.timezone ?? null,
    timezoneAbbreviation: raw.timezoneAbbreviation ?? null,
    observedAt: raw.observedAt ?? null,
    weather: raw.weather ?? null,
    daily: Array.isArray(raw.daily) ? raw.daily : [],
    dailyText: raw.dailyText ?? '',
    weatherError: raw.weatherError ?? null,
    sources: raw.sources ?? { weather: null, location: null },
  };
}
export function weatherLabel(code: number | null | undefined): string {
  if (code == null) return '天气未知';
  if (code === 0) return '晴';
  if (code <= 3) return '多云';
  if (code <= 48) return '雾';
  if (code <= 67) return '雨';
  if (code <= 77) return '雪';
  if (code <= 82) return '阵雨';
  if (code <= 86) return '阵雪';
  return '雷雨';
}
