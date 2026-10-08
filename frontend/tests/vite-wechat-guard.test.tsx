import { test } from 'node:test';
import assert from 'node:assert/strict';
import { localWechatOnly } from '../local-wechat-only';

test('phone-mode proxy never forwards WeChat QR routes from a LAN device', () => {
  const plugin = localWechatOnly();
  let guard!: Parameters<Parameters<typeof plugin.configureServer>[0]['middlewares']['use']>[0];
  plugin.configureServer({
    middlewares: {
      use(handler) {
        guard = handler;
      },
    },
  });
  const response = () => {
    const headers: Record<string, string> = {};
    let body = '';
    return {
      headers,
      get body() {
        return body;
      },
      statusCode: 200,
      setHeader(name: string, value: string) {
        headers[name] = value;
      },
      end(value: string) {
        body = value;
      },
    };
  };
  let passed = 0;
  const lan = response();
  guard(
    { url: '/api/wechat/status', socket: { remoteAddress: '192.168.1.3' } },
    lan,
    () => passed++,
  );
  assert.equal(lan.statusCode, 403);
  assert.equal(lan.headers['Cache-Control'], 'no-store');
  assert.equal(passed, 0);
  const reminder = response();
  guard(
    { url: '/api/wechat/reminders/test/retry', socket: { remoteAddress: '192.168.1.3' } },
    reminder,
    () => passed++,
  );
  assert.equal(reminder.statusCode, 403);
  assert.equal(passed, 0);
  const local = response();
  guard(
    { url: '/api/wechat/status', socket: { remoteAddress: '::ffff:127.0.0.1' } },
    local,
    () => passed++,
  );
  assert.equal(passed, 1);
  const otherRoute = response();
  guard({ url: '/api/chat', socket: { remoteAddress: '192.168.1.3' } }, otherRoute, () => passed++);
  assert.equal(passed, 2);
});
