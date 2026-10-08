import { useState } from 'react';
import { CloudSun, LocateFixed, MapPin, RefreshCw, X } from 'lucide-react';
import type { LiveContext } from '@/features/context/types';
import { weatherLabel } from '@/features/context/weather';
import { locationAccuracyText } from '@/features/context/device-location';
import { placeDisplayName, timezoneDisplayName } from '@/features/context/display-labels';

function OriginalPlaceName({ location }: { location: string | null }) {
  const { original } = placeDisplayName(location);
  if (!original) return null;
  return (
    <details className="nx-weather-source nx-weather-original-name">
      <summary>查看接口原始地名</summary>
      <span>{original}</span>
    </details>
  );
}

export function ContextPanel({
  context,
  candidate,
  now,
  busy,
  error,
  onLocate,
  onSearch,
  onClear,
  onConfirmLocation,
}: {
  context: LiveContext | null;
  candidate: LiveContext | null;
  now: Date;
  busy: boolean;
  error: string;
  onLocate: () => void;
  onSearch: (city: string) => void;
  onClear: () => void;
  onConfirmLocation: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [city, setCity] = useState('');
  const [days, setDays] = useState(false);
  const w = context?.weather;
  const number = (n: number | null | undefined, unit: string) =>
    n == null ? '—' : `${Math.round(n * 10) / 10}${unit}`;
  return (
    <section className="nx-weather" aria-label="位置、天气与时间">
      <div className="nx-weather-title">
        <span>
          <CloudSun size={16} />
          当地天气
        </span>
        <button
          className="nx-icon-button"
          title="设置天气位置"
          aria-label="设置天气位置"
          onClick={() => setExpanded(!expanded)}
        >
          <MapPin size={15} />
        </button>
      </div>
      {candidate ? (
        <div className="nx-weather-candidate" role="status">
          <p>定位结果待确认</p>
          <strong>{placeDisplayName(candidate.location).label}</strong>
          <p className="nx-weather-source">{locationAccuracyText(candidate.accuracy)}</p>
          <p className="nx-weather-source">
            这是你当前所在的位置吗？电脑定位可能偏差较大，估计精度不保证城市正确。确认前不会随问题发送。
          </p>
          <details className="nx-weather-source">
            <summary>查看定位坐标</summary>
            <span>
              {candidate.latitude.toFixed(5)}, {candidate.longitude.toFixed(5)}（浏览器提供）
            </span>
          </details>
          <OriginalPlaceName location={candidate.location} />
          <div className="nx-weather-location-actions">
            <button type="button" onClick={onConfirmLocation}>
              位置正确，使用此天气
            </button>
            <button
              type="button"
              onClick={() => {
                onClear();
                setExpanded(true);
              }}
            >
              城市不对，手动选择
            </button>
          </div>
        </div>
      ) : context ? (
        <>
          <div className="nx-weather-current">
            <strong>{number(w?.temperature, '°')}</strong>
            <div>
              <b>{w ? weatherLabel(w.weatherCode) : '天气暂不可用'}</b>
              <span>{placeDisplayName(context.location).label}</span>
            </div>
          </div>
          <div className="nx-weather-meta">
            <span>湿度 {number(w?.humidity, '%')}</span>
            <span>风速 {number(w?.windSpeed, ' 千米/小时')}</span>
          </div>
          <p className="nx-weather-source">
            {context.method === 'manual'
              ? '手动城市 · 非田块定位'
              : `设备位置 · 已由你确认 · ${locationAccuracyText(context.accuracy)}`}
          </p>
          <OriginalPlaceName location={context.location} />
          {w && (
            <p className="nx-weather-source">
              {context.sources.weather || '天气接口'} ·{' '}
              {context.observedAt?.replace('T', ' ') || '更新时间未提供'}
            </p>
          )}
          {context.weatherError && (
            <p role="status" className="nx-weather-error">
              {context.weatherError}
            </p>
          )}
          {context.daily.length > 0 && (
            <button className="nx-text-button" onClick={() => setDays(!days)}>
              {days ? '收起预报' : '查看未来天气'} →
            </button>
          )}
          {days && (
            <div className="nx-weather-days">
              {context.daily.map((day) => (
                <div key={day.date}>
                  <span>{day.date.slice(5)}</span>
                  <span>{weatherLabel(day.weatherCode)}</span>
                  <b>
                    {number(day.tempMin, '')} / {number(day.tempMax, '°')}
                  </b>
                </div>
              ))}
            </div>
          )}
        </>
      ) : (
        <div className="nx-weather-empty">
          <p>还未选择位置</p>
          <button onClick={() => setExpanded(true)}>选择城市，获取真实天气 →</button>
          <button type="button" disabled={busy} onClick={onLocate}>
            <LocateFixed size={13} />
            {busy ? '正在定位…' : '使用当前位置'}
          </button>
        </div>
      )}
      {expanded && (
        <form
          className="nx-weather-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (city.trim()) onSearch(city.trim());
          }}
        >
          <label className="sr-only" htmlFor="weather-city">
            天气城市
          </label>
          <div>
            <input
              id="weather-city"
              value={city}
              maxLength={80}
              placeholder="城市，例如：杭州市"
              onChange={(e) => setCity(e.target.value)}
            />
            <button type="submit" disabled={busy || !city.trim()} aria-label="查询天气">
              <RefreshCw size={15} className={busy ? 'spin' : ''} />
            </button>
          </div>
          <button type="button" disabled={busy} className="nx-text-button" onClick={onLocate}>
            <LocateFixed size={13} />
            {busy ? '正在获取…' : '使用设备位置'}
          </button>
          {(context || candidate) && (
            <button type="button" className="nx-text-button" disabled={busy} onClick={onClear}>
              <X size={13} />
              清除本次位置
            </button>
          )}
          <p className="nx-weather-source">
            定位需浏览器与系统授权；获取到城市后请核对。城市查询无需位置权限，仍不代表田块精确位置。
          </p>
        </form>
      )}
      {error && (
        <p className="nx-weather-error" role="alert">
          {error}
        </p>
      )}
      <div className="nx-clock">
        <span>
          {now.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })}
        </span>
        <time>
          {now.toLocaleTimeString('zh-CN', {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false,
          })}
        </time>
      </div>
      <p className="nx-weather-source">
        本机时间 · {timezoneDisplayName(Intl.DateTimeFormat().resolvedOptions().timeZone, now)}
      </p>
    </section>
  );
}
