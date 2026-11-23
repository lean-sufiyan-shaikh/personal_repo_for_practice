import { formatIstDate } from '../../../utils/date'

// Cyclic color palette for shift rows — index into this by (sortOrder - 1)
export const SHIFT_ROW_COLORS = [
  { bg: 'bg-info-bg',    text: 'text-info' },
  { bg: 'bg-warn-bg',    text: 'text-warn' },
  { bg: 'bg-ok-bg',      text: 'text-ok' },
  { bg: 'bg-neutral-bg', text: 'text-neutral' },
]

export const STATUS_BADGE: Record<string, string> = {
  PLANNED:   'bg-surface-sunken text-ink-2',
  CONFIRMED: 'bg-ok-bg text-ok',
  ABSENT:    'bg-bad-bg text-bad',
}

export function formatDay(iso: string): string {
  return formatIstDate(iso, { weekday: 'short', day: 'numeric', month: 'short' })
}
