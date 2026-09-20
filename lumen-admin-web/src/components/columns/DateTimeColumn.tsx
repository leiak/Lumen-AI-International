/**
 * DateTimeColumn — render a date-time string in consistent format.
 *
 * Backend sends ISO-8601 strings (e.g. "2026-09-20T14:30:00Z").
 * Display as "2026-09-20 14:30:00" (local time, no timezone suffix).
 * Empty/null/undefined renders as "—" to avoid NaN noise.
 */

interface DateTimeColumnProps {
  value: string | number | Date | null | undefined;
  /** Override the format. Defaults to "YYYY-MM-DD HH:mm:ss". */
  format?: Intl.DateTimeFormatOptions;
}

function formatValue(value: DateTimeColumnProps['value'], opts: Intl.DateTimeFormatOptions): string {
  if (value == null || value === '') return '—';
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return '—';
  // Use a stable, sortable representation rather than locale-dependent Intl.
  const pad = (n: number) => String(n).padStart(2, '0');
  const y = d.getFullYear();
  const m = pad(d.getMonth() + 1);
  const day = pad(d.getDate());
  const hh = pad(d.getHours());
  const mm = pad(d.getMinutes());
  const ss = pad(d.getSeconds());
  if (opts.year && opts.month && opts.day && !opts.hour) {
    return `${y}-${m}-${day}`;
  }
  return `${y}-${m}-${day} ${hh}:${mm}:${ss}`;
}

export function DateTimeColumn({ value, format: opts }: DateTimeColumnProps) {
  const text = formatValue(
    value,
    opts ?? { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' },
  );
  return <span>{text}</span>;
}