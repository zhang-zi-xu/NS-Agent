import './setup';
import { afterEach, mock, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import App from '../src/App';
import {
  DEFAULT_SETTINGS,
  PROVIDERS,
  readSettings,
  SETTINGS_KEY,
  writeSettings,
} from '@/features/settings/storage';

const originalFetch = globalThis.fetch;
afterEach(() => {
  cleanup();
  mock.restoreAll();
  sessionStorage.clear();
  localStorage.clear();
  globalThis.fetch = originalFetch;
});

test('fresh settings default to deepseek-flash without a credential', () => {
  sessionStorage.clear();
  const settings = readSettings();
  assert.equal(settings.model, 'deepseek-flash');
  assert.equal(settings.provider, 'deepseek');
  assert.equal(settings.apiKey, '');
  assert.notEqual(settings, DEFAULT_SETTINGS);
});

test('selecting the DeepSeek preset uses deepseek-flash', () => {
  assert.equal(PROVIDERS.find((provider) => provider.id === 'deepseek')?.model, 'deepseek-flash');
});

test('a new default does not overwrite a previously saved model', () => {
  const saved = { ...DEFAULT_SETTINGS, model: 'my-chosen-model' };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(saved));
  assert.deepEqual(readSettings(), saved);
});

test('saved credentials and vision settings survive a new browser session', () => {
  const saved = { ...DEFAULT_SETTINGS, apiKey: 'test-only-persisted-key', vision: 'off' as const };
  writeSettings(saved);
  sessionStorage.clear();
  assert.deepEqual(readSettings(), saved);
});

test('old session credentials migrate once without changing the chosen model', () => {
  const legacy = { ...DEFAULT_SETTINGS, model: 'chosen-model', apiKey: 'test-only-legacy-key' };
  sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(legacy));
  assert.deepEqual(readSettings(), legacy);
  assert.deepEqual(JSON.parse(localStorage.getItem(SETTINGS_KEY)!), legacy);
  assert.equal(sessionStorage.getItem(SETTINGS_KEY), null);
});

test('a cleared persistent key takes precedence over a stale legacy session key', () => {
  const legacy = { ...DEFAULT_SETTINGS, apiKey: 'test-only-stale-key' };
  writeSettings(DEFAULT_SETTINGS);
  sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(legacy));
  assert.equal(readSettings().apiKey, '');
});

test('damaged persistent settings never resurrect a legacy key', () => {
  sessionStorage.setItem(
    SETTINGS_KEY,
    JSON.stringify({ ...DEFAULT_SETTINGS, apiKey: 'test-only-old-key' }),
  );
  for (const raw of ['{invalid', '{"provider":"unknown"}', 'null']) {
    localStorage.setItem(SETTINGS_KEY, raw);
    assert.deepEqual(readSettings(), DEFAULT_SETTINGS);
  }
});

test('storage failure is reported without leaking credentials or destroying the old record', () => {
  const old = { ...DEFAULT_SETTINGS, apiKey: 'test-only-old-key' };
  writeSettings(old);
  mock.method(Object.getPrototypeOf(localStorage), 'setItem', () => {
    throw new Error('storage rejected test-only-new-secret');
  });
  assert.throws(
    () => writeSettings({ ...old, apiKey: 'test-only-new-secret' }),
    (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.match(error.message, /本地存储权限/);
      assert.doesNotMatch(error.message, /test-only-new-secret/);
      return true;
    },
  );
  assert.deepEqual(readSettings(), old);
});

test('failed migration retains the usable legacy settings for the current page', () => {
  const legacy = { ...DEFAULT_SETTINGS, apiKey: 'test-only-legacy-key' };
  sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(legacy));
  mock.method(Object.getPrototypeOf(localStorage), 'setItem', () => {
    throw new Error('blocked');
  });
  assert.deepEqual(readSettings(), legacy);
  assert.equal(localStorage.getItem(SETTINGS_KEY), null);
  assert.notEqual(sessionStorage.getItem(SETTINGS_KEY), null);
});

async function openWorkspaceSettings() {
  globalThis.fetch = async (input) => {
    const route = String(input);
    const body = route === '/api/health' ? { status: 'ok' } : [];
    assert.ok(
      ['/api/health', '/api/fields', '/api/tasks', '/api/conversations', '/api/knowledge'].includes(
        route,
      ),
      'Settings must not send credentials or make a model request on save.',
    );
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  };
  render(<App />);
  await screen.findByText('Java 服务已连接');
  fireEvent.click(screen.getByRole('button', { name: '模型设置' }));
}

test('saving through the page persists across remount and clearing removes the remembered key', async () => {
  await openWorkspaceSettings();
  fireEvent.change(screen.getByPlaceholderText('可留空，使用管理员配置的演示额度'), {
    target: { value: 'test-only-ui-persisted-key' },
  });
  fireEvent.click(screen.getByRole('button', { name: '保存设置' }));
  await screen.findByText('模型设置已保存在当前浏览器，重启后可继续使用。');
  cleanup();
  sessionStorage.clear();
  await openWorkspaceSettings();
  assert.equal(
    (screen.getByPlaceholderText('可留空，使用管理员配置的演示额度') as HTMLInputElement).value,
    'test-only-ui-persisted-key',
  );
  fireEvent.click(screen.getByRole('button', { name: '清除已保存密钥' }));
  assert.equal(readSettings().apiKey, '');
  sessionStorage.clear();
  assert.equal(readSettings().apiKey, '');
});

test('a custom-provider key can be forgotten without switching provider or testing a model', async () => {
  writeSettings({
    provider: 'custom',
    model: 'chosen-model',
    baseUrl: 'https://example.test/v1',
    apiKey: 'test-only-custom-key',
  });
  await openWorkspaceSettings();
  fireEvent.click(screen.getByRole('button', { name: '清除已保存密钥' }));
  assert.equal(readSettings().apiKey, '');
  assert.equal(readSettings().provider, 'custom');
});

test('blocked persistence keeps the settings dialog open and never claims a successful save', async () => {
  await openWorkspaceSettings();
  mock.method(Object.getPrototypeOf(localStorage), 'setItem', () => {
    throw new Error('private storage detail test-only-not-saved-key');
  });
  fireEvent.change(screen.getByPlaceholderText('可留空，使用管理员配置的演示额度'), {
    target: { value: 'test-only-not-saved-key' },
  });
  fireEvent.click(screen.getByRole('button', { name: '保存设置' }));
  assert.match(screen.getByRole('alert').textContent!, /本地存储权限/);
  assert.doesNotMatch(screen.getByRole('alert').textContent!, /test-only-not-saved-key/);
  assert.ok(screen.getByRole('button', { name: '保存设置' }));
  assert.equal(screen.queryByText('模型设置已保存在当前浏览器，重启后可继续使用。'), null);
  assert.equal(readSettings().apiKey, '');
});

test('entering a complete Key persists immediately without save or connection test', async () => {
  await openWorkspaceSettings();
  fireEvent.change(screen.getByPlaceholderText('可留空，使用管理员配置的演示额度'), {
    target: { value: 'test-only-automatic-key' },
  });
  assert.equal(readSettings().apiKey, 'test-only-automatic-key');
  assert.ok(screen.getByText('已自动保存到当前浏览器，关闭后无需重新填写。'));
  assert.ok(screen.getByRole('button', { name: '保存设置' }));
  cleanup();
  sessionStorage.clear();
  await openWorkspaceSettings();
  assert.equal(
    (screen.getByPlaceholderText('可留空，使用管理员配置的演示额度') as HTMLInputElement).value,
    'test-only-automatic-key',
  );
});

test('partial and empty input never replace the remembered working Key', async () => {
  writeSettings({ ...DEFAULT_SETTINGS, apiKey: 'test-only-working-key' });
  await openWorkspaceSettings();
  const input = screen.getByPlaceholderText('可留空，使用管理员配置的演示额度');
  fireEvent.change(input, { target: { value: 'short' } });
  assert.equal(readSettings().apiKey, 'test-only-working-key');
  fireEvent.change(input, { target: { value: '' } });
  assert.equal(readSettings().apiKey, 'test-only-working-key');
  assert.ok(screen.getByText('当前内容尚未完整，原已保存设置保留；填写完整 Key 后自动保存。'));
});

test('opening settings re-reads the saved record rather than a stale workspace value', async () => {
  await openWorkspaceSettings();
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  writeSettings({
    ...DEFAULT_SETTINGS,
    model: 'externally-saved-model',
    apiKey: 'test-only-new-record-key',
  });
  fireEvent.click(screen.getByRole('button', { name: '模型设置' }));
  assert.equal(
    (screen.getByPlaceholderText('可留空，使用管理员配置的演示额度') as HTMLInputElement).value,
    'test-only-new-record-key',
  );
  assert.equal(
    (screen.getByPlaceholderText('填写供应商支持的完整模型 ID') as HTMLInputElement).value,
    'externally-saved-model',
  );
});

test('silent storage failure cannot be reported as automatic save success', async () => {
  await openWorkspaceSettings();
  mock.method(Object.getPrototypeOf(localStorage), 'setItem', () => {});
  fireEvent.change(screen.getByPlaceholderText('可留空，使用管理员配置的演示额度'), {
    target: { value: 'test-only-not-retained-key' },
  });
  assert.match(screen.getByRole('alert').textContent!, /本地存储权限/);
  assert.equal(screen.queryByText('已自动保存到当前浏览器，关闭后无需重新填写。'), null);
  assert.equal(readSettings().apiKey, '');
});
