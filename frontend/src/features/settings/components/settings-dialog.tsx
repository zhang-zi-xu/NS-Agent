import { useState } from 'react';
import { Check, Eye, EyeOff, ShieldCheck } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/shared/ui/dialog';
import { NxSelect } from '@/shared/ui/nx-select';
import { api, errorText } from '@/shared/api/client';
import { PROVIDERS } from '@/features/settings/storage';
import { type AiSettings } from '@/features/settings/types';
import { normalizeSettings } from '@/features/settings/validation';

export function SettingsDialog({
  open,
  onOpenChange,
  settings,
  onSave,
  onAutoSave,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  settings: AiSettings;
  onSave: (settings: AiSettings) => void;
  onAutoSave: (settings: AiSettings) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="nx-dialog nx-settings">
        <DialogTitle>连接你的模型</DialogTitle>
        <DialogDescription>
          填入自己的密钥可使用所选供应商和模型；预设供应商留空密钥时，实际改用管理员配置的演示供应商与模型。
        </DialogDescription>
        {open && <SettingsForm settings={settings} onSave={onSave} onAutoSave={onAutoSave} />}
      </DialogContent>
    </Dialog>
  );
}
function SettingsForm({
  settings,
  onSave,
  onAutoSave,
}: {
  settings: AiSettings;
  onSave: (settings: AiSettings) => void;
  onAutoSave: (settings: AiSettings) => void;
}) {
  const [draft, setDraft] = useState({ ...settings });
  const [showKey, setShowKey] = useState(false);
  const [error, setError] = useState('');
  const [testing, setTesting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [saved, setSaved] = useState<'idle' | 'saved' | 'pending' | 'failed'>('idle');
  function valid() {
    return normalizeSettings(draft);
  }
  function updateDraft(next: AiSettings) {
    setDraft(next);
    setSuccess(false);
    setError('');
    // An empty/partial key or unfinished provider must not silently replace a usable saved key.
    let value: AiSettings;
    try {
      if (!next.apiKey.trim()) {
        setSaved('pending');
        return;
      }
      value = normalizeSettings(next);
    } catch {
      setSaved('pending');
      return;
    }
    try {
      onAutoSave(value);
      setSaved('saved');
    } catch (cause) {
      setSaved('failed');
      setError(errorText(cause));
    }
  }
  async function test() {
    setError('');
    setSuccess(false);
    try {
      const value = valid();
      setTesting(true);
      const result = await api<{ reply: string }>('/chat', {
        method: 'POST',
        body: JSON.stringify({
          ...value,
          messages: [{ role: 'user', content: '这是一条连接测试。请只回复：连接成功。' }],
        }),
      });
      if (!result.reply)
        throw new Error('接口已返回，但未收到有效回答。请检查模型名称及接口兼容性。');
      setSuccess(true);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setTesting(false);
    }
  }
  return (
    <form
      className="nx-form"
      onSubmit={(e) => {
        e.preventDefault();
        try {
          onSave(valid());
        } catch (err) {
          setError(errorText(err));
        }
      }}
    >
      <label className="nx-form-field">
        供应商
        <NxSelect
          ariaLabel="供应商"
          value={draft.provider}
          disabled={testing}
          options={PROVIDERS.map((p) => ({ value: p.id, label: p.label }))}
          onValueChange={(id) => {
            if (id === draft.provider) return;
            const p = PROVIDERS.find((p) => p.id === id)!;
            updateDraft({ provider: p.id, baseUrl: p.baseUrl, model: p.model, apiKey: '' });
          }}
        />
      </label>
      <label className="nx-form-field">
        API 地址
        <input
          type="url"
          autoComplete="off"
          value={draft.baseUrl}
          disabled={draft.provider !== 'custom' || testing}
          placeholder="https://你的服务商/v1"
          onChange={(e) => {
            updateDraft({ ...draft, baseUrl: e.target.value });
          }}
        />
      </label>
      <label className="nx-form-field">
        模型名称
        <input
          autoComplete="off"
          disabled={testing}
          value={draft.model}
          placeholder="填写供应商支持的完整模型 ID"
          maxLength={200}
          onChange={(e) => {
            updateDraft({ ...draft, model: e.target.value });
          }}
        />
      </label>
      <label className="nx-form-field">
        API Key
        <div className="nx-secret-input">
          <input
            autoComplete="off"
            spellCheck={false}
            disabled={testing}
            type={showKey ? 'text' : 'password'}
            value={draft.apiKey}
            placeholder={
              draft.provider === 'custom'
                ? '自定义供应商必须填写'
                : '可留空，使用管理员配置的演示额度'
            }
            onChange={(e) => {
              updateDraft({ ...draft, apiKey: e.target.value });
            }}
          />
          <button
            type="button"
            className="nx-icon-button"
            aria-label={showKey ? '隐藏密钥' : '显示密钥'}
            onClick={() => setShowKey(!showKey)}
          >
            {showKey ? <EyeOff size={17} /> : <Eye size={17} />}
          </button>
        </div>
        <small role="status" className={saved === 'failed' ? 'nx-warning' : 'nx-muted'}>
          {saved === 'saved'
            ? '已自动保存到当前浏览器，关闭后无需重新填写。'
            : saved === 'failed'
              ? '自动保存失败，请处理下方提示后重试。'
              : saved === 'pending'
                ? '当前内容尚未完整，原已保存设置保留；填写完整 Key 后自动保存。'
                : settings.apiKey
                  ? '已读取当前浏览器保存的密钥。'
                  : '填写完整 Key 后自动保存，无需另点保存。'}
        </small>
      </label>
      <label className="nx-form-field">
        图片输入
        <NxSelect
          ariaLabel="图片输入"
          value={draft.vision ?? 'auto'}
          disabled={testing}
          options={[
            { value: 'auto', label: '自动判断（推荐）', hint: '按模型名单' },
            { value: 'on', label: '支持看图', hint: '我的模型支持' },
            { value: 'off', label: '不支持', hint: '纯文本模型' },
          ]}
          onValueChange={(value) => {
            updateDraft({ ...draft, vision: value as 'auto' | 'on' | 'off' });
          }}
        />
      </label>
      <div className="nx-security-note">
        <ShieldCheck size={18} />
        <p>
          图片输入按模型名单自动判断；名单里没有的模型会被明确拒绝，不会把照片发给不看图的模型。照片在浏览器里压缩并去除
          GPS 等 EXIF 信息，服务端会再清理一次。
        </p>
      </div>
      <div className="nx-security-note">
        <ShieldCheck size={18} />
        <p>
          格式完整的自有密钥和模型设置会自动以明文保存在当前浏览器本地，关闭网页或重启后仍会保留，请勿在公用设备填写。
          若主动连接微信，密钥会临时交给本机 Java 服务；停止连接或服务重启后清除后端内存副本，
          不写入后端数据库或日志。留空以使用演示额度需点击保存设置；删除输入框内容不会自动清除已保存密钥。清除网页密钥不会停止已有微信连接，
          需另点“停止本机连接”。
        </p>
      </div>
      {error && (
        <p role="alert" className="nx-error">
          {error}
        </p>
      )}
      {success && (
        <p role="status" className="nx-success">
          <Check size={15} />
          已收到模型回答，连接可用。
        </p>
      )}
      <div className="nx-dialog-actions">
        <button
          className="nx-button"
          type="button"
          disabled={testing || !settings.apiKey}
          onClick={() => {
            try {
              onSave({ ...settings, apiKey: '' });
              setDraft((previous) => ({ ...previous, apiKey: '' }));
              setSuccess(false);
              setError('');
            } catch (err) {
              setError(errorText(err));
            }
          }}
        >
          清除已保存密钥
        </button>
        <button className="nx-button" type="button" disabled={testing} onClick={() => void test()}>
          {testing ? '正在测试…' : '测试连接'}
        </button>
        <button className="nx-button is-primary" disabled={testing} type="submit">
          保存设置
        </button>
      </div>
      <p className="nx-muted nx-fine">
        测试会向所选供应商发送一条简短请求，可能产生少量 API 费用。模型名称请以供应商控制台为准。
      </p>
    </form>
  );
}
