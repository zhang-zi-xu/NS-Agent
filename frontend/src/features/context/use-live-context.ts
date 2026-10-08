import { useEffect, useRef, useState } from 'react';
import { api, errorText } from '@/shared/api/client';
import { normalizeContext } from './weather';
import { DEVICE_LOCATION_OPTIONS, deviceLocationIssue } from './device-location';
import type { LiveContext } from './types';

/** A location request cannot overwrite a newer city choice or a cleared context. */
export function useLiveContext(beforeChange: () => void) {
  const [context, setContext] = useState<LiveContext | null>(null);
  const [pendingContext, setPendingContext] = useState<LiveContext | null>(null);
  const [contextBusy, setContextBusy] = useState(false);
  const [contextError, setContextError] = useState('');
  const [now, setNow] = useState(() => new Date());
  const contextSeq = useRef(0);
  const locateTried = useRef(false);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => {
      window.clearInterval(timer);
      contextSeq.current++;
    };
  }, []);
  async function fetchContext(
    params: URLSearchParams,
    method: 'device' | 'manual',
    accuracy: number,
    sequence: number,
    locatedAt = Date.now(),
  ) {
    try {
      const raw = await api<LiveContext>(`/context?${params}&days=7`);
      if (sequence === contextSeq.current) {
        if (
          method === 'device' &&
          (!Number.isFinite(raw.latitude) ||
            !Number.isFinite(raw.longitude) ||
            Math.abs(raw.latitude - Number(params.get('lat'))) > 0.00001 ||
            Math.abs(raw.longitude - Number(params.get('lon'))) > 0.00001)
        ) {
          throw new Error(
            '天气接口返回的位置与设备坐标不一致，暂不采用。请重新定位或手动选择城市。',
          );
        }
        const next = {
          ...normalizeContext(raw, method, accuracy),
          locatedAt,
          ...(method === 'device'
            ? { latitude: Number(params.get('lat')), longitude: Number(params.get('lon')) }
            : {}),
        };
        // Even a claimed 500m accuracy can accompany a wrong city. Do not attach the
        // candidate to chat until the user recognises and confirms the place.
        if (method === 'device') setPendingContext(next);
        else setContext(next);
      }
    } catch (e) {
      if (sequence === contextSeq.current) setContextError(errorText(e));
    } finally {
      if (sequence === contextSeq.current) setContextBusy(false);
    }
  }
  function searchCity(city: string) {
    beforeChange();
    locateTried.current = true;
    const seq = ++contextSeq.current;
    setContextBusy(true);
    setContextError('');
    setContext(null);
    setPendingContext(null);
    void fetchContext(new URLSearchParams({ city }), 'manual', 0, seq);
  }
  // 没位置时自动定位：仅在浏览器已经授权定位的情况下静默获取，
  // 未授权时不弹权限框（一进页面就弹窗很打扰，也可能被浏览器直接拦），交给用户点「使用当前位置」
  useEffect(() => {
    if (context || pendingContext || contextBusy || contextError || locateTried.current) return;
    const permissions = navigator.permissions;
    if (!permissions?.query) return;
    const seq = contextSeq.current;
    let active = true;
    try {
      permissions
        .query({ name: 'geolocation' as PermissionName })
        .then((result) => {
          if (!active || seq !== contextSeq.current) return;
          locateTried.current = true;
          if (result.state === 'granted') locate();
        })
        .catch(() => {
          /* 查询权限失败就不自动定位，用户仍可手动点 */
        });
    } catch {
      /* 个别浏览器不支持查询该权限名时会同步抛错，绝不能让它把页面搞崩 */
    }
    return () => {
      active = false;
    };
  }, [context, pendingContext, contextBusy, contextError]);

  function locate() {
    beforeChange();
    locateTried.current = true;
    const seq = ++contextSeq.current;
    setContext(null);
    setPendingContext(null);
    setContextBusy(false);
    if (!window.isSecureContext || !navigator.geolocation) {
      setContextError('当前环境不支持设备定位，请手动选择城市；远程访问需 HTTPS。');
      return;
    }
    setContextBusy(true);
    setContextError('');
    try {
      navigator.geolocation.getCurrentPosition(
        (p) => {
          if (seq !== contextSeq.current) return;
          const issue = deviceLocationIssue(p);
          if (issue) {
            setContextBusy(false);
            setContextError(issue);
            return;
          }
          void fetchContext(
            new URLSearchParams({
              lat: String(p.coords.latitude),
              lon: String(p.coords.longitude),
            }),
            'device',
            p.coords.accuracy,
            seq,
            p.timestamp,
          );
        },
        (e) => {
          if (seq !== contextSeq.current) return;
          setContextBusy(false);
          setContextError(
            e.code === 1
              ? '定位权限未获允许。可在浏览器中开启权限，或手动输入城市。'
              : '设备定位失败或超时，请手动输入城市查询天气。',
          );
        },
        DEVICE_LOCATION_OPTIONS,
      );
    } catch {
      if (seq !== contextSeq.current) return;
      setContextBusy(false);
      setContextError('设备定位暂不可用，请在浏览器与系统中检查位置权限，或手动选择城市。');
    }
  }
  function confirmLocation() {
    if (!pendingContext) return;
    beforeChange();
    contextSeq.current++;
    setContextBusy(false);
    if (Date.now() - pendingContext.locatedAt > 5 * 60000) {
      setPendingContext(null);
      setContextError('该定位结果已过期，请重新定位或手动选择城市。');
      return;
    }
    setContext(pendingContext);
    setPendingContext(null);
    setContextError('');
  }
  function clearContext() {
    beforeChange();
    locateTried.current = true;
    contextSeq.current++;
    setContext(null);
    setPendingContext(null);
    setContextError('');
    setContextBusy(false);
  }
  return {
    context,
    pendingContext,
    contextBusy,
    contextError,
    now,
    locate,
    confirmLocation,
    searchCity,
    clearContext,
  };
}
