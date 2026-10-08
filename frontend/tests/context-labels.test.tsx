import './setup';
import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { cleanup, render, screen } from '@testing-library/react';
import { ContextPanel } from '@/features/context/components/context-panel';
import { placeDisplayName, timezoneDisplayName } from '@/features/context/display-labels';
import { normalizeContext } from '@/features/context/weather';

afterEach(cleanup);
const rawName = 'Lima · Villa El Salvador · Provincia de Lima';
const chineseName = '利马 · 萨尔瓦多镇 · 利马省';
const now = new Date('2026-10-03T13:09:43Z');

test('known geocoder names have Chinese display labels and retain the exact original', () => {
  assert.deepEqual(placeDisplayName(rawName), { label: chineseName, original: rawName });
  assert.equal(placeDisplayName('lima · VILLA EL SALVADOR').label, '利马 · 萨尔瓦多镇');
});

test('Chinese place names remain unchanged and missing names are explained', () => {
  assert.deepEqual(placeDisplayName('江苏省 · 宿迁市'), {
    label: '江苏省 · 宿迁市',
    original: null,
  });
  for (const value of [null, undefined, '', ' ']) {
    assert.deepEqual(placeDisplayName(value), { label: '位置名称未获取', original: null });
  }
});

test('untranslated names get an honest Chinese notice, never a fabricated Chinese city', () => {
  assert.deepEqual(placeDisplayName('Unknown City · Unknown District'), {
    label: '地名暂无中文名称',
    original: 'Unknown City · Unknown District',
  });
  assert.deepEqual(placeDisplayName('江苏省 · Unknown District'), {
    label: '江苏省 · 部分地名暂无中文名称',
    original: '江苏省 · Unknown District',
  });
  assert.equal(placeDisplayName('__proto__').label, '地名暂无中文名称');
});

test('the device UTC+8 time zone has a readable Chinese name with correct offset sign', () => {
  assert.equal(timezoneDisplayName('Etc/GMT-8', now), '北京时间（UTC+08:00）');
  assert.equal(timezoneDisplayName('Asia/Shanghai', now), '北京时间（UTC+08:00）');
  assert.equal(timezoneDisplayName('Etc/GMT+8', now), '本地时区（UTC-08:00）');
});

test('other time zones are localized and invalid zones do not leak raw identifiers', () => {
  assert.match(timezoneDisplayName('America/New_York', now), /[\u3400-\u9fff]/);
  assert.equal(timezoneDisplayName('not-a-timezone', now), '时区未识别');
});

for (const pending of [true, false]) {
  test(`${pending ? 'pending' : 'confirmed'} location shows Chinese without changing location data`, () => {
    const context = normalizeContext(
      { location: rawName, latitude: -12.2, longitude: -76.95 },
      'device',
      500,
    );
    const before = JSON.stringify(context);
    render(
      <ContextPanel
        context={pending ? null : context}
        candidate={pending ? context : null}
        now={now}
        busy={false}
        error=""
        onLocate={() => {}}
        onSearch={() => {}}
        onClear={() => {}}
        onConfirmLocation={() => {}}
      />,
    );
    assert.ok(screen.getByText(chineseName));
    const original = screen.getByText(rawName);
    const disclosure = original.closest('details');
    assert.ok(disclosure);
    assert.equal(disclosure.open, false, 'the raw name should not dominate the weather card');
    assert.equal(disclosure.querySelector('summary')?.textContent, '查看接口原始地名');
    assert.equal(
      JSON.stringify(context),
      before,
      'localization must not rewrite the geocoder payload',
    );
    assert.equal(screen.queryByText('宿迁市'), null, 'translation is not location correction');
  });
}

test('unknown original names stay available for location confirmation', () => {
  render(
    <ContextPanel
      context={null}
      candidate={normalizeContext({ location: 'Unknown City' }, 'device', 500)}
      now={now}
      busy={false}
      error=""
      onLocate={() => {}}
      onSearch={() => {}}
      onClear={() => {}}
      onConfirmLocation={() => {}}
    />,
  );
  assert.ok(screen.getByText('地名暂无中文名称'));
  assert.ok(screen.getByText('Unknown City').closest('details'));
  assert.ok(screen.getByRole('button', { name: '城市不对，手动选择' }));
});
