/** datetime-local is deliberately interpreted as Beijing time, not the device timezone. */
export function beijingInput(instant: string): string {
  const date = new Date(instant);
  return Number.isFinite(date.getTime())
    ? new Date(date.getTime() + 8 * 3600_000).toISOString().slice(0, 16)
    : '';
}
export function reminderInstant(input: string): string | null {
  if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d$/.test(input)) return null;
  const date = new Date(`${input}:00+08:00`);
  if (!Number.isFinite(date.getTime()) || beijingInput(date.toISOString()) !== input) return null;
  return date.toISOString();
}
export function defaultReminder(date: string, now = Date.now()): string {
  const value = reminderInstant(`${date}T09:00`);
  return value && Date.parse(value) > now ? beijingInput(value) : '';
}
