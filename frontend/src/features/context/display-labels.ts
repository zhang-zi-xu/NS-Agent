// Display-only aliases; never replace the coordinates or raw geocoder result.
// District/province names: Wikidata Q691525 and Q579240.
const placeNames = new Map([
  ['lima', '利马'],
  ['villa el salvador', '萨尔瓦多镇'],
  ['provincia de lima', '利马省'],
]);

export function placeDisplayName(location?: string | null) {
  const original = location?.trim() || '';
  if (!original) return { label: '位置名称未获取', original: null };

  const translated: string[] = [];
  let missing = false;
  for (const part of original
    .split('·')
    .map((value) => value.trim())
    .filter(Boolean)) {
    const name = placeNames.get(part.toLowerCase());
    if (name) translated.push(name);
    else if (/[\u3400-\u9fff]/.test(part) && !/[a-z]/i.test(part)) translated.push(part);
    else missing = true;
  }
  if (missing && translated.length) translated.push('部分地名暂无中文名称');
  const label = translated.join(' · ') || '地名暂无中文名称';
  return { label, original: label === original ? null : original };
}

export function timezoneDisplayName(timeZone: string, now: Date): string {
  try {
    // Validate first, including the supplied clock value.
    const name = new Intl.DateTimeFormat('zh-CN', {
      timeZone,
      timeZoneName: 'long',
    })
      .formatToParts(now)
      .find((part) => part.type === 'timeZoneName')?.value;
    if (timeZone === 'Asia/Shanghai' || timeZone === 'Etc/GMT-8') {
      return '北京时间（UTC+08:00）';
    }
    if (name && /[\u3400-\u9fff]/.test(name)) return name;
    const offset = new Intl.DateTimeFormat('zh-CN', {
      timeZone,
      timeZoneName: 'longOffset',
    })
      .formatToParts(now)
      .find((part) => part.type === 'timeZoneName')?.value;
    return offset ? `本地时区（${offset.replace('GMT', 'UTC')}）` : '本地时区';
  } catch {
    return '时区未识别';
  }
}
