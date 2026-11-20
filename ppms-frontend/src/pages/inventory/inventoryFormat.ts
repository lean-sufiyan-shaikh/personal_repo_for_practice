import { formatIstDate } from '../../utils/date'

// ── Colour helpers ─────────────────────────────────────────────────────────────

export const FUEL_COLORS: Record<string, { bar: string; text: string; badge: string }> = {
  PETROL: { bar: 'bg-emerald-500', text: 'text-emerald-700', badge: 'bg-emerald-100 text-emerald-700' },
  DIESEL: { bar: 'bg-blue-500',    text: 'text-blue-700',    badge: 'bg-blue-100 text-blue-700'    },
  CNG:    { bar: 'bg-amber-500',   text: 'text-amber-700',   badge: 'bg-amber-100 text-amber-700'  },
}

export function limitDecimals(value: string, places: number): string {
  const dot = value.indexOf('.')
  if (dot === -1) return value
  return value.slice(0, dot + 1 + places)
}

export function fmtQty(n: number | null | undefined) {
  if (n == null) return '—'
  return `${n.toLocaleString('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} L`
}

export function fmtDate(s: string) {
  return formatIstDate(s)
}
