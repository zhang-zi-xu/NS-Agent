export type ConnectionState =
  | 'DISCONNECTED'
  | 'GENERATING'
  | 'WAITING'
  | 'SCANNED'
  | 'CONNECTED'
  | 'EXPIRED'
  | 'ERROR';
export type ConnectionStatus = {
  state: ConnectionState;
  qrContent?: string | null;
  detail?: string | null;
  connectedAt?: string | null;
  provider?: string | null;
  model?: string | null;
};
