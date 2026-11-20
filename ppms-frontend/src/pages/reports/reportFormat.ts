import { formatIstDate, formatIstDateTime, localDateInputValue } from '../../utils/date'

// ── Helpers ───────────────────────────────────────────────────────────────────

export function fmtAmt(n: number) {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export function fmtQty(n: number) {
  return n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' L'
}

export function todayIso()     { return localDateInputValue() }

export function yesterdayIso() { return localDateInputValue(-1) }

export function fmtDateTime(iso: string) {
  return formatIstDateTime(iso)
}

export function fmtShortDate(iso: string) {
  return formatIstDate(iso, { day: '2-digit', month: 'short', year: 'numeric' })
}

export function fmtShortDayMonth(iso: string) {
  return formatIstDate(iso, { day: '2-digit', month: 'short' })
}
