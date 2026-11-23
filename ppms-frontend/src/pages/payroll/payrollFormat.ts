import { Check, Hourglass, Wallet } from 'lucide-react'
import type { PayrollStatus } from '../../api/payrollApi'
import type { StatusBadgeSpec } from '../../components/statusBadges'
import { codeLabel } from '../../constants/labels'
import { localDateInputValue } from '../../utils/date'

export const STATUS_BADGES: Record<PayrollStatus, StatusBadgeSpec> = {
  DRAFT:    { tone: 'warn', icon: Hourglass, label: codeLabel('DRAFT') },
  APPROVED: { tone: 'info', icon: Check, label: codeLabel('APPROVED') },
  PAID:     { tone: 'ok', icon: Wallet, label: codeLabel('PAID') },
}

// Order in which status groups are displayed
export const STATUS_ORDER: PayrollStatus[] = ['DRAFT', 'APPROVED', 'PAID']

export const NEXT_STATUS: Partial<Record<PayrollStatus, PayrollStatus>> = {
  DRAFT:    'APPROVED',
  APPROVED: 'PAID',
}

export { formatCurrency as fmtAmt } from '../../utils/format'

export { formatIstDate as fmtDate } from '../../utils/date'

/** Returns "2026-04" from a date string */
export function toMonthKey(dateStr: string): string {
  return dateStr.slice(0, 7)
}

/** Returns "Apr 2026" from "2026-04" */
export function fmtMonthKey(key: string): string {
  const [year, month] = key.split('-')
  return new Date(Number(year), Number(month) - 1, 1)
    .toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', month: 'short', year: 'numeric' })
}

export function todayIso()     { return localDateInputValue() }

export function yesterdayIso() { return localDateInputValue(-1) }
