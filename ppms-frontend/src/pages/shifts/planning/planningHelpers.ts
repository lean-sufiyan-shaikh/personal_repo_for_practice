import { formatIstDate } from '../../../utils/date'

// Cyclic color palette for shift rows — index into this by (sortOrder - 1)
export const SHIFT_ROW_COLORS = [
  { bg: 'bg-indigo-50',  text: 'text-indigo-700' },
  { bg: 'bg-amber-50',   text: 'text-amber-700'  },
  { bg: 'bg-emerald-50', text: 'text-emerald-700'},
  { bg: 'bg-rose-50',    text: 'text-rose-700'   },
]

export const STATUS_BADGE: Record<string, string> = {
  PLANNED:   'bg-slate-100 text-slate-600',
  CONFIRMED: 'bg-emerald-100 text-emerald-700',
  ABSENT:    'bg-red-100 text-red-600',
}

// Return Monday of the week containing `date`
export function getMonday(date: Date): Date {
  const d = new Date(date)
  const day = d.getDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setDate(d.getDate() + diff)
  d.setHours(0, 0, 0, 0)
  return d
}

// Use local date components — toISOString() converts to UTC first, causing off-by-one day errors in non-UTC timezones
export function toISODate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function formatDay(iso: string): string {
  return formatIstDate(iso, { weekday: 'short', day: 'numeric', month: 'short' })
}
