/**
 * DateTimeColumn — render a date-time string in consistent format.
 *
 * Backend sends ISO-8601 strings (e.g. "2026-09-20T14:30:00Z").
 * Display as "2026-09-20 14:30:00" (local time, no timezone suffix).
 * Empty/null/undefined renders as "—" to avoid NaN noise.
 */

interface DateTimeColumnProps {
  value: string | number | Date | null | undefined;
  /** mode = 'date' renders YYYY-MM-DD; default 'datetime' renders YYYY-MM-DD HH:mm:ss. */
  format?: 'datetime' | 'date';
}

function format(value: DateTimeColumnProps['value'], mode: 'datetime' | 'date'): string {
  if (value == null || value === '') return '—';
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  const y = d.getFullYear();
  const m = pad(d.getMonth() + 1);
  const day = pad(d.getDate());
  if (mode === 'date') {
    return `${y}-${m}-${day}`;
  }
  const hh = pad(d.getHours());
  const mm = pad(d.getMinutes());
  const ss = pad(d.getSeconds());
  return `${y}-${m}-${day} ${hh}:${mm}:${ss}`;
}

export function DateTimeColumn({ value, format: mode }: DateTimeColumnProps) {
  const text = format(value, mode ?? 'datetime');
  return <span>{text}</span>;
}