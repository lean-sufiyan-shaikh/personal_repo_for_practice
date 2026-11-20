import { formatIstDate, formatIstDateTime } from '../../utils/date'

// ── Date helpers ───────────────────────────────────────────────────────────────
// Use local calendar date (not UTC) so the value matches the user's timezone.


// ── Formatting helpers ──────────────────────────────────────────────────────────

export const IST_TIME_FORMATTER = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  hour: '2-digit',
  minute: '2-digit',
})

export function fmtMoney(n: number) {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export function fmtLitres(n: number) {
  return `${n.toLocaleString('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} L`
}

export function fmtDate(iso: string) {
  return formatIstDate(iso)
}

export function fmtDateTime(iso: string) {
  return formatIstDateTime(iso)
}

export const FUEL_BADGE: Record<string, string> = {
  PETROL:       'bg-emerald-100 text-emerald-700',
  DIESEL:       'bg-blue-100    text-blue-700',
  SPEED_PETROL: 'bg-violet-100  text-violet-700',
  CNG:          'bg-amber-100   text-amber-700',
}
