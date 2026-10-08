import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import { useLiveContext } from '@/features/context/use-live-context';
import { ContextPanel } from '@/features/context/components/context-panel';
import { deviceLocationIssue, locationAccuracyText } from '@/features/context/device-location';
import { normalizeContext } from '@/features/context/weather';

const originalFetch = globalThis.fetch;
const originalGeo = Object.getOwnPropertyDescriptor(navigator, 'geolocation');
const originalPermission = Object.getOwnPropertyDescriptor(navigator, 'permissions');
const originalSecure = Object.getOwnPropertyDescriptor(window, 'isSecureContext');
afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  for (const [target, name, descriptor] of [
    [navigator, 'geolocation', originalGeo],
    [navigator, 'permissions', originalPermission],
    [window, 'isSecureContext', originalSecure],
  ] as const) {
    if (descriptor) Object.defineProperty(target, name, descriptor);
    else Reflect.deleteProperty(target, name);
  }
});
const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  });
const position = (latitude = 32.19, longitude = 119.43, accuracy = 500, timestamp = Date.now()) =>
  ({
    coords: {
      latitude,
      longitude,
      accuracy,
      altitude: null,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
    },
    timestamp,
  }) as GeolocationPosition;
const rawContext = (location: string) => ({
  location,
  latitude: location === '宿迁市' ? 33.96 : 32.19,
  longitude: location === '宿迁市' ? 118.28 : 119.43,
  weather: null,
  sources: { weather: null, location: '测试地名解析' },
});
function fixture() {
  const requests: string[] = [];
  const callbacks: Array<{
    success: PositionCallback;
    error?: PositionErrorCallback | null;
    options?: PositionOptions;
  }> = [];
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition(
        success: PositionCallback,
        error?: PositionErrorCallback | null,
        options?: PositionOptions,
      ) {
        callbacks.push({ success, error, options });
      },
    },
  });
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    return json(rawContext(url.includes('city=') ? '宿迁市' : '镇江市'));
  };
  return { requests, callbacks };
}

test('fresh high accuracy request still requires city confirmation even at reported 500m', async () => {
  const f = fixture();
  const { result } = renderHook(() => useLiveContext(() => {}));
  const activeContext = () => result.current.context;
  act(() => result.current.locate());
  assert.deepEqual(f.callbacks[0].options, {
    enableHighAccuracy: true,
    timeout: 20000,
    maximumAge: 0,
  });
  act(() => f.callbacks[0].success(position()));
  await waitFor(() => assert.equal(result.current.pendingContext?.location, '镇江市'));
  assert.equal(activeContext(), null, 'an estimated 500m radius must not make a wrong city active');
  assert.equal(result.current.pendingContext?.accuracy, 500);
  act(() => result.current.confirmLocation());
  assert.equal(activeContext()?.location, '镇江市');
  assert.equal(result.current.pendingContext, null);
});

test('invalid and stale device positions fail without querying weather', () => {
  const f = fixture();
  const { result } = renderHook(() => useLiveContext(() => {}));
  for (const value of [
    position(NaN),
    position(91),
    position(32, Infinity),
    position(32, 119, -1),
    position(32, 119, 500, Date.now() - 61000),
  ]) {
    act(() => result.current.locate());
    act(() => f.callbacks.at(-1)!.success(value));
    assert.equal(result.current.context, null);
    assert.equal(result.current.pendingContext, null);
    assert.equal(result.current.contextBusy, false);
    assert.ok(result.current.contextError);
  }
  assert.equal(f.requests.length, 0);
});

test('manual city choice wins over a late device callback', async () => {
  const f = fixture();
  const { result } = renderHook(() => useLiveContext(() => {}));
  act(() => result.current.locate());
  act(() => result.current.searchCity('宿迁'));
  await waitFor(() => assert.equal(result.current.context?.location, '宿迁市'));
  act(() => f.callbacks[0].success(position()));
  assert.equal(result.current.context?.method, 'manual');
  assert.equal(result.current.pendingContext, null);
  assert.equal(f.requests.length, 1);
});

test('manual city choice also wins over a late geocode and weather response', async () => {
  const f = fixture();
  let finish!: (response: Response) => void;
  globalThis.fetch = async (input) =>
    String(input).includes('city=')
      ? json(rawContext('宿迁市'))
      : new Promise<Response>((resolve) => {
          finish = resolve;
        });
  const { result } = renderHook(() => useLiveContext(() => {}));
  act(() => result.current.locate());
  act(() => f.callbacks[0].success(position()));
  act(() => result.current.searchCity('宿迁市'));
  await waitFor(() => assert.equal(result.current.context?.location, '宿迁市'));
  await act(async () => finish(json(rawContext('镇江市'))));
  assert.equal(result.current.context?.location, '宿迁市');
  assert.equal(result.current.pendingContext, null);
});

test('a delayed granted-permission result cannot override manual city selection', async () => {
  const f = fixture();
  let finish!: (permission: PermissionStatus) => void;
  Object.defineProperty(navigator, 'permissions', {
    configurable: true,
    value: {
      query: () =>
        new Promise<PermissionStatus>((resolve) => {
          finish = resolve;
        }),
    },
  });
  const { result } = renderHook(() => useLiveContext(() => {}));
  act(() => result.current.searchCity('宿迁市'));
  await waitFor(() => assert.equal(result.current.context?.location, '宿迁市'));
  await act(async () => finish({ state: 'granted' } as PermissionStatus));
  assert.equal(f.callbacks.length, 0);
  assert.equal(result.current.context?.location, '宿迁市');
});

test('clearing location prevents delayed callbacks and confirmation from restoring it', async () => {
  const f = fixture();
  const { result } = renderHook(() => useLiveContext(() => {}));
  act(() => result.current.locate());
  act(() => result.current.clearContext());
  act(() => f.callbacks[0].success(position()));
  assert.equal(f.requests.length, 0);
  act(() => result.current.locate());
  act(() => f.callbacks[1].success(position()));
  await waitFor(() => assert.ok(result.current.pendingContext));
  act(() => result.current.clearContext());
  act(() => result.current.confirmLocation());
  assert.equal(result.current.context, null);
  assert.equal(result.current.pendingContext, null);
});

test('permission denial, timeout and synchronous browser failure leave no active location', () => {
  const f = fixture();
  const { result } = renderHook(() => useLiveContext(() => {}));
  for (const code of [1, 3]) {
    act(() => result.current.locate());
    act(() => f.callbacks.at(-1)!.error?.({ code } as GeolocationPositionError));
    assert.equal(result.current.contextBusy, false);
    assert.equal(result.current.context, null);
    assert.ok(result.current.contextError);
  }
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition() {
        throw new Error('test-only browser error');
      },
    },
  });
  act(() => result.current.locate());
  assert.equal(result.current.contextBusy, false);
  assert.match(result.current.contextError, /设备定位暂不可用/);
  assert.equal(f.requests.length, 0);
});

test('expired pending location cannot become active on confirmation', async () => {
  const f = fixture();
  const { result } = renderHook(() => useLiveContext(() => {}));
  act(() => result.current.locate());
  act(() => f.callbacks[0].success(position()));
  await waitFor(() => assert.ok(result.current.pendingContext));
  const originalNow = Date.now;
  try {
    const later = originalNow() + 6 * 60000;
    Date.now = () => later;
    act(() => result.current.confirmLocation());
    assert.equal(result.current.context, null);
    assert.match(result.current.contextError, /已过期/);
  } finally {
    Date.now = originalNow;
  }
});

test('candidate panel offers confirmation or direct manual correction without showing active weather', () => {
  let confirmed = 0;
  let cleared = 0;
  const searches: string[] = [];
  render(
    <ContextPanel
      context={null}
      candidate={normalizeContext(rawContext('镇江市'), 'device', 500)}
      now={new Date()}
      busy={false}
      error=""
      onLocate={() => {}}
      onClear={() => {
        cleared++;
      }}
      onConfirmLocation={() => {
        confirmed++;
      }}
      onSearch={(city) => searches.push(city)}
    />,
  );
  assert.ok(screen.getByText('定位结果待确认'));
  assert.ok(screen.getByText('设备估计精度约 500 米'));
  fireEvent.click(screen.getByRole('button', { name: '位置正确，使用此天气' }));
  assert.equal(confirmed, 1);
  fireEvent.click(screen.getByRole('button', { name: '城市不对，手动选择' }));
  assert.equal(cleared, 1);
  fireEvent.change(screen.getByRole('textbox', { name: '天气城市' }), {
    target: { value: '宿迁' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询天气' }));
  assert.deepEqual(searches, ['宿迁']);
});

test('accuracy formatting never presents zero or invalid accuracy as precise', () => {
  assert.equal(locationAccuracyText(0), '估计精度未提供');
  assert.equal(locationAccuracyText(NaN), '估计精度未提供');
  assert.equal(locationAccuracyText(15000), '设备估计精度约 15.0 公里');
  assert.equal(deviceLocationIssue(position(), Date.now()), null);
});

test('weather for a different coordinate cannot be accepted as the device location', async () => {
  const f = fixture();
  globalThis.fetch = async () => json({ ...rawContext('解析候选'), latitude: 0, longitude: 0 });
  const { result } = renderHook(() => useLiveContext(() => {}));
  act(() => result.current.locate());
  act(() => f.callbacks[0].success(position(33.96, 118.28)));
  await waitFor(() => assert.match(result.current.contextError, /设备坐标不一致/));
  assert.equal(result.current.pendingContext, null);
  assert.equal(result.current.context, null);
});
