import {
  useEffect,
  useState,
  type PointerEvent as ReactPointerEvent,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react';

// 两侧栏宽度：可拖动/键盘调节，记住上次的选择（纯界面偏好，与业务数据无关）
export const SIDEBAR_DEFAULT = 230,
  SIDEBAR_MIN = 180,
  SIDEBAR_MAX = 460,
  SIDEBAR_KEY = 'nongxin-sidebar-width';
export const RAIL_DEFAULT = 250,
  RAIL_MIN = 200,
  RAIL_MAX = 420,
  RAIL_KEY = 'nongxin-rail-width';
const clampWidth = (value: number, min: number, max: number) =>
  Math.round(Math.min(max, Math.max(min, value)));
function readPanelWidth(key: string, fallback: number, min: number, max: number) {
  try {
    const stored = Number(localStorage.getItem(key));
    return Number.isFinite(stored) && stored > 0 ? clampWidth(stored, min, max) : fallback;
  } catch {
    return fallback;
  }
}
/**
 * 可调节宽度的侧栏。
 * direction = 1 表示面板在左侧（向右拖变宽），-1 表示面板在右侧（向左拖变宽）；
 * 方向键始终让分隔条朝着按键方向移动，与鼠标拖动的手感一致。
 */
export function usePanelWidth(
  key: string,
  initial: number,
  min: number,
  max: number,
  direction: 1 | -1,
) {
  const [width, setWidth] = useState(() => readPanelWidth(key, initial, min, max));
  const [resizing, setResizing] = useState(false);
  useEffect(() => {
    try {
      localStorage.setItem(key, String(width));
    } catch {
      /* 存储不可用时忽略，不影响使用 */
    }
  }, [key, width]);
  const apply = (value: number) => setWidth(clampWidth(value, min, max));
  function startResize(event: ReactPointerEvent<HTMLDivElement>) {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = width;
    setResizing(true);
    const move = (moveEvent: PointerEvent) =>
      apply(startWidth + direction * (moveEvent.clientX - startX));
    const finish = () => {
      setResizing(false);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', finish);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', finish);
  }
  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 48 : 16;
    const widenKey = direction === 1 ? 'ArrowRight' : 'ArrowLeft';
    const narrowKey = direction === 1 ? 'ArrowLeft' : 'ArrowRight';
    if (event.key === widenKey) {
      event.preventDefault();
      apply(width + step);
    } else if (event.key === narrowKey) {
      event.preventDefault();
      apply(width - step);
    } else if (event.key === 'Home' || event.key === 'Escape') {
      event.preventDefault();
      apply(initial);
    }
  }
  return { width, resizing, startResize, onKeyDown, reset: () => apply(initial), min, max };
}
/** 媒体查询订阅：用于按屏宽决定天气模块放在右侧栏还是左侧栏。 */
export function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(() =>
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia(query).matches
      : true,
  );
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener?.('change', update);
    return () => list.removeEventListener?.('change', update);
  }, [query]);
  return matches;
}
