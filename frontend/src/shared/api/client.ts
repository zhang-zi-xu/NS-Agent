export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new Error('无法连接 Java 服务，请检查后端是否已启动。');
  }
  const data = await response.json().catch(() => null);
  if (!response.ok)
    throw new Error(
      typeof data?.error === 'string'
        ? data.error
        : `请求失败（${response.status}），请检查后端服务。`,
    );
  if (data === null && response.status !== 204) throw new Error('服务返回了无效的数据格式。');
  return data as T;
}
export const errorText = (error: unknown) =>
  error instanceof Error ? error.message : '操作失败，请重试。';
export const uid = () => crypto.randomUUID();
