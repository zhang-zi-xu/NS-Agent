import { useEffect, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { Link2, LoaderCircle, RefreshCw, ShieldCheck, Unplug, TriangleAlert } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/shared/ui/dialog';
import { api, errorText } from '@/shared/api/client';
import { PROVIDERS } from '@/features/settings/storage';
import { type AiSettings } from '@/features/settings/types';

import type { ConnectionState, ConnectionStatus } from '../types';

const labels: Record<ConnectionState, string> = {
  DISCONNECTED: '尚未连接',
  GENERATING: '正在生成二维码…',
  WAITING: '等待微信扫码',
  SCANNED: '已扫码，请在微信中确认',
  CONNECTED: '微信已连接',
  EXPIRED: '二维码已过期',
  ERROR: '连接遇到问题',
};

export function WechatDialog({
  open,
  onOpenChange,
  settings,
  onOpenSettings,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  settings: AiSettings;
  onOpenSettings: () => void;
}) {
  const [status, setStatus] = useState<ConnectionStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [pollError, setPollError] = useState('');

  useEffect(() => {
    if (!open) return;
    let active = true;
    async function update() {
      try {
        const next = await api<ConnectionStatus>('/wechat/status');
        if (active) {
          setStatus(next);
          setPollError('');
        }
      } catch (cause) {
        if (active) setPollError(errorText(cause));
      }
    }
    void update();
    const timer = window.setInterval(() => void update(), 1800);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [open]);

  async function perform(action: 'connect' | 'refresh' | 'disconnect') {
    if (action === 'connect' && !settings.apiKey.trim()) {
      setError('微信端需要自有 API Key。请先打开模型设置填写，保存后再连接。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const next = await api<ConnectionStatus>(`/wechat/${action}`, {
        method: 'POST',
        body: JSON.stringify(action === 'connect' ? settings : {}),
      });
      setStatus(next);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  const state = status?.state ?? 'DISCONNECTED';
  const canRefresh = state === 'EXPIRED' || state === 'ERROR';
  const boundProvider = status?.provider || settings.provider;
  const provider = PROVIDERS.find((item) => item.id === boundProvider)?.label ?? boundProvider;
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="nx-dialog nx-wechat-dialog">
        <DialogTitle>
          <Link2 size={19} /> 连接微信
        </DialogTitle>
        <DialogDescription>
          在这台电脑上接入微信，随后可以在微信里向农心发文字提问。它与“手机访问网页”不同。
        </DialogDescription>
        <div className="nx-wechat-model">
          <span>{status?.provider ? '微信当前使用的模型' : '准备使用的模型'}</span>
          <strong>
            {provider} · {status?.model || settings.model || '未设置模型'}
          </strong>
          <button type="button" className="nx-text-button" onClick={onOpenSettings}>
            修改网页设置
          </button>
        </div>
        <div className={`nx-wechat-state is-${state.toLowerCase()}`} role="status">
          {state === 'GENERATING' ? (
            <LoaderCircle className="spin" size={17} />
          ) : (
            <span className="nx-connection-dot is-online" />
          )}
          <b>{labels[state]}</b>
        </div>
        {status?.qrContent && (state === 'WAITING' || state === 'SCANNED') && (
          <div className="nx-wechat-qr">
            <QRCodeSVG
              value={status.qrContent}
              size={208}
              marginSize={2}
              aria-label="微信连接二维码"
            />
            <p>
              {state === 'SCANNED'
                ? '请回到微信确认登录。'
                : '请用自己的微信扫描此码。二维码只在本机页面展示。'}
            </p>
          </div>
        )}
        {state === 'CONNECTED' && (
          <div className="nx-wechat-connected">
            <ShieldCheck size={20} />
            <div>
              <b>可以开始聊天了</b>
              <p>
                先在微信向农心发送一条消息，即可在网页任务中设置微信提醒。保持这台电脑上的农心服务运行。关闭此弹窗或浏览器，不会断开连接。更改网页模型后，需要停止并重新连接微信才会生效。
              </p>
            </div>
          </div>
        )}
        {status?.detail && <p className="nx-muted">{status.detail}</p>}
        {error && (
          <p role="alert" className="nx-error">
            {error}
          </p>
        )}
        {!error && pollError && (
          <p role="alert" className="nx-error">
            {pollError}
          </p>
        )}
        <div className="nx-wechat-notes">
          <TriangleAlert size={16} />
          <p>
            只处理本次扫码账号的私聊。微信问答与网页资料独立；任务摘要仅在你启用并保存提醒后发送。聊天记录保存在本机数据库，模型请求仍会发送给所选供应商。微信通道使用第三方
            SDK。
          </p>
        </div>
        <div className="nx-dialog-actions">
          {state !== 'DISCONNECTED' && (
            <button
              className="nx-button"
              type="button"
              disabled={busy}
              onClick={() => void perform('disconnect')}
            >
              <Unplug size={15} />
              停止本机连接
            </button>
          )}
          {canRefresh && (
            <button
              className="nx-button is-primary"
              type="button"
              disabled={busy}
              onClick={() => void perform('refresh')}
            >
              <RefreshCw size={15} />
              重新生成二维码
            </button>
          )}
          {state === 'DISCONNECTED' && (
            <button
              className="nx-button is-primary"
              type="button"
              disabled={busy}
              onClick={() => void perform('connect')}
            >
              {busy ? '正在连接…' : '生成连接二维码'}
            </button>
          )}
        </div>
        <p className="nx-muted nx-fine">
          停止本机连接不会撤销微信侧授权；农心服务重启后需重新扫码。请勿向他人展示二维码。
        </p>
      </DialogContent>
    </Dialog>
  );
}
