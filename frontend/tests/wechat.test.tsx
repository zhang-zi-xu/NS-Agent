import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { WechatDialog } from '@/features/wechat/components/wechat-dialog';
import type { AiSettings } from '@/features/settings/types';

afterEach(() => cleanup());
const model: AiSettings = {
  provider: 'deepseek',
  model: 'test-model',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: 'test-only-local-key',
};
const json = (body: unknown) =>
  new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });

test('wechat connection requires own key and never sends the server demo key', async () => {
  const original = fetch;
  const calls: string[] = [];
  globalThis.fetch = async (input) => {
    calls.push(String(input));
    return json({ state: 'DISCONNECTED' });
  };
  try {
    render(
      <WechatDialog
        open
        onOpenChange={() => {}}
        settings={{ ...model, apiKey: '' }}
        onOpenSettings={() => {}}
      />,
    );
    await screen.findByText('尚未连接');
    fireEvent.click(screen.getByRole('button', { name: '生成连接二维码' }));
    assert.ok(screen.getByRole('alert').textContent?.includes('自有 API Key'));
    assert.equal(
      calls.some((call) => call.endsWith('/connect')),
      false,
    );
  } finally {
    globalThis.fetch = original;
  }
});

test('wechat QR flow shows scan, expiry, refresh and local disconnect', async () => {
  const original = fetch;
  let current = { state: 'DISCONNECTED', qrContent: null as string | null };
  const requests: Array<{ url: string; body: unknown }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, body: init?.body ? JSON.parse(String(init.body)) : null });
    if (url.endsWith('/connect'))
      current = { state: 'WAITING', qrContent: 'https://example.test/qr' };
    if (url.endsWith('/refresh'))
      current = { state: 'WAITING', qrContent: 'https://example.test/qr-new' };
    if (url.endsWith('/disconnect')) current = { state: 'DISCONNECTED', qrContent: null };
    return json(current);
  };
  try {
    const ui = render(
      <WechatDialog open onOpenChange={() => {}} settings={model} onOpenSettings={() => {}} />,
    );
    await screen.findByText('尚未连接');
    fireEvent.click(screen.getByRole('button', { name: '生成连接二维码' }));
    await screen.findByLabelText('微信连接二维码');
    const connect = requests.find((row) => row.url.endsWith('/connect'))!;
    assert.deepEqual(connect.body, model);
    current = { state: 'EXPIRED', qrContent: null };
    ui.rerender(
      <WechatDialog
        open={false}
        onOpenChange={() => {}}
        settings={model}
        onOpenSettings={() => {}}
      />,
    );
    ui.rerender(
      <WechatDialog open onOpenChange={() => {}} settings={model} onOpenSettings={() => {}} />,
    );
    await screen.findByText('二维码已过期');
    fireEvent.click(screen.getByRole('button', { name: '重新生成二维码' }));
    await waitFor(() =>
      assert.equal(requests.filter((row) => row.url.endsWith('/refresh')).length, 1),
    );
    fireEvent.click(screen.getByRole('button', { name: '停止本机连接' }));
    await screen.findByText('尚未连接');
    assert.equal(requests.filter((row) => row.url.endsWith('/disconnect')).length, 1);
  } finally {
    globalThis.fetch = original;
  }
});
