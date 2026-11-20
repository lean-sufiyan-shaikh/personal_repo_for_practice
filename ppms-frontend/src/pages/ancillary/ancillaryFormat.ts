import type { AncillaryPaymentMode, UnitOfMeasure } from '../../api/ancillaryApi'
import { formatIstDate } from '../../utils/date'

// ── Helpers ─────────────────────────────────────────────────────────────────

export function fmtCurrency(n: number | null | undefined) {
  if (n == null) return '—'
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export function fmtDate(s: string) {
  return formatIstDate(s)
}

export const PAYMENT_MODES: AncillaryPaymentMode[] = ['CASH', 'UPI', 'CARD', 'FLEET_CARD', 'CREDIT']

export const UNITS: UnitOfMeasure[] = ['LITRE', 'KG', 'PIECE']
