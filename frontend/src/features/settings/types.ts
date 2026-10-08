export type AiSettings = {
  provider: string;
  model: string;
  baseUrl: string;
  apiKey: string;
  vision?: 'auto' | 'on' | 'off';
};
