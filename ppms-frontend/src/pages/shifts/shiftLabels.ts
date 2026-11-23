import { formatIstDate, formatIstDateTime, localDateInputValue } from '../../utils/date'
import type { Shift, ShiftStatus } from '../../types/shift'
import type { UserRole } from '../../types/auth'
import { canPerformAction } from '../../permissions/permissions'

// ── Constants ────────────────────────────────────────────────────────────────

const STATUS_LABELS: Record<ShiftStatus, string> = {
  OPEN:                                'Open',
  OPEN_OVERDUE:                        'Overdue',
  AUTO_CLOSED_OVERDUE:                 'Auto-closed',
  CLOSED_BALANCED:                     'Balanced',
  CLOSED_DISCREPANCY_PENDING:          'Discrepancy',
  CLOSED_DISCREPANCY_PENDING_APPROVAL: 'Needs Approval',
  CLOSED_DISCREPANCY_RESOLVED:         'Resolved',
}

export function statusLabel(status: ShiftStatus): string {
  return STATUS_LABELS[status] ?? status
}

export const AWAITING_OWNER_APPROVAL = 'Waiting for owner approval'

/**
 * What the signed-in user can do about an unresolved discrepancy: a shortage escalated for approval
 * is only for OWNER/ADMIN, everything else pending is for whoever may resolve discrepancies.
 */
export function discrepancyAction(shift: Shift, role: UserRole | undefined): 'resolve' | 'approve' | 'awaiting-owner' | null {
  if (shift.status === 'CLOSED_DISCREPANCY_PENDING_APPROVAL') {
    return role === 'OWNER' || role === 'ADMIN' ? 'approve' : 'awaiting-owner'
  }
  if (shift.status === 'CLOSED_DISCREPANCY_PENDING' && canPerformAction('shift:resolve-discrepancy', role)) {
    return 'resolve'
  }
  return null
}

export const PAGE_SIZE = 10

// ── Date helpers ─────────────────────────────────────────────────────────────


export function labelForDate(d: string): string {
  const todayStr     = localDateInputValue()
  const yesterdayStr = localDateInputValue(-1)
  const dayBeforeStr = localDateInputValue(-2)
  if (d === todayStr)     return 'Today'
  if (d === yesterdayStr) return 'Yesterday'
  if (d === dayBeforeStr) return 'Day Before Yesterday'
  return formatIstDate(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
}

export { formatCurrencyOrDash as fmtAmt } from '../../utils/format'

// ── Discrepancies History ─────────────────────────────────────────────────────

export const RESOLUTION_LABELS: Record<string, string> = {
  PENDING_INVESTIGATION: 'Pending Investigation',
  SALARY_DEDUCTION:      'Salary Deduction',
  CASH_RECOVERY:         'Cash Recovery',
  WAIVED:                'Waived',
}

/** "Under investigation by … since …" while a pending discrepancy is being investigated, otherwise null. */
export function investigationLabel(shift: Shift): string | null {
  const isPending = shift.status === 'CLOSED_DISCREPANCY_PENDING' || shift.status === 'CLOSED_DISCREPANCY_PENDING_APPROVAL'
  if (!isPending || !shift.investigationStartedAt) return null
  return `Under investigation by ${shift.investigatedByName ?? 'unknown user'} since ${formatIstDateTime(shift.investigationStartedAt)}`
}

// ── Resolve Discrepancy Modal ─────────────────────────────────────────────────

export const RESOLUTION_OPTIONS = [
  { value: 'PENDING_INVESTIGATION', label: 'Pending Investigation' },
  { value: 'SALARY_DEDUCTION',      label: 'Salary Deduction' },
  { value: 'CASH_RECOVERY',         label: 'Cash Recovery' },
  { value: 'WAIVED',                label: 'Waived' },
]
