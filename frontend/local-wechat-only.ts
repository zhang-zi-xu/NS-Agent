// The phone-mode Vite proxy can make a LAN visitor look like 127.0.0.1 to Java.
// Reject WeChat QR/admin routes before proxying, using the original socket peer.
export function localWechatOnly() {
  const protect = (
    req: { url?: string; socket: { remoteAddress?: string } },
    res: {
      statusCode: number;
      setHeader: (name: string, value: string) => void;
      end: (body: string) => void;
    },
    next: () => void,
  ) => {
    if (!req.url?.startsWith('/api/wechat')) return next();
    const peer = req.socket.remoteAddress ?? '';
    if (peer === '::1' || peer.startsWith('127.') || peer.startsWith('::ffff:127.')) return next();
    res.statusCode = 403;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    res.end(JSON.stringify({ error: '微信连接仅可从本机页面操作' }));
  };
  return {
    name: 'nongxin-local-wechat-only',
    configureServer(server: { middlewares: { use: (handler: typeof protect) => void } }) {
      server.middlewares.use(protect);
    },
    configurePreviewServer(server: { middlewares: { use: (handler: typeof protect) => void } }) {
      server.middlewares.use(protect);
    },
  };
}
