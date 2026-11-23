import { AlertTriangle, ArrowDown, ArrowUp, Ban, Check, CircleDot, Clock, FileCheck, Gauge, Hourglass, Pause, X } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { DiscrepancyType, ShiftStatus } from '../types/shift'
import type { CreditInvoiceStatus, CreditStanding } from '../api/creditApi'
import type { ExpenseApprovalStatus } from '../api/expenseApi'
import { codeLabel } from '../constants/labels'
import { statusLabel } from '../pages/shifts/shiftLabels'
import type { StatusTone } from './StatusBadge'

export interface StatusBadgeSpec {
  tone: StatusTone
  icon: LucideIcon
  label: string
}

const SHIFT_STATUS: Record<ShiftStatus, Omit<StatusBadgeSpec, 'label'>> = {
  OPEN: { tone: 'ok', icon: CircleDot },
  OPEN_OVERDUE: { tone: 'warn', icon: Clock },
  AUTO_CLOSED_OVERDUE: { tone: 'bad', icon: AlertTriangle },
  CLOSED_BALANCED: { tone: 'neutral', icon: Check },
  CLOSED_DISCREPANCY_PENDING: { tone: 'bad', icon: AlertTriangle },
  CLOSED_DISCREPANCY_PENDING_APPROVAL: { tone: 'warn', icon: Hourglass },
  CLOSED_DISCREPANCY_RESOLVED: { tone: 'neutral', icon: Check },
}

export function shiftStatusBadge(status: ShiftStatus): StatusBadgeSpec {
  const look = SHIFT_STATUS[status] ?? { tone: 'neutral', icon: CircleDot }
  return { ...look, label: statusLabel(status) }
}

/** SHORT is money missing (bad), OVER is money extra (warn); null means the shift balanced. */
export function discrepancyBadge(type: DiscrepancyType | null): StatusBadgeSpec {
  if (type === 'SHORT') return { tone: 'bad', icon: ArrowDown, label: 'SHORT' }
  if (type === 'OVER') return { tone: 'warn', icon: ArrowUp, label: 'OVER' }
  return { tone: 'ok', icon: Check, label: 'Balanced' }
}

const APPROVAL_STATUS: Record<ExpenseApprovalStatus, Omit<StatusBadgeSpec, 'label'>> = {
  DRAFT: { tone: 'neutral', icon: Pause },
  PENDING_APPROVAL: { tone: 'warn', icon: Hourglass },
  APPROVED: { tone: 'ok', icon: Check },
  REJECTED: { tone: 'bad', icon: X },
}

export function approvalStatusBadge(status: ExpenseApprovalStatus): StatusBadgeSpec {
  return { ...APPROVAL_STATUS[status], label: codeLabel(status) }
}

export function invoiceStatusBadge(status: CreditInvoiceStatus): StatusBadgeSpec {
  return status === 'CANCELLED'
    ? { tone: 'bad', icon: Ban, label: codeLabel(status) }
    : { tone: 'ok', icon: FileCheck, label: codeLabel(status) }
}

const CREDIT_STANDING: Record<CreditStanding, Omit<StatusBadgeSpec, 'label'>> = {
  OK: { tone: 'ok', icon: Check },
  OVERDUE: { tone: 'bad', icon: Clock },
  OVER_LIMIT: { tone: 'warn', icon: Gauge },
  INACTIVE: { tone: 'neutral', icon: Pause },
}

export function creditStandingBadge(standing: CreditStanding): StatusBadgeSpec {
  return { ...CREDIT_STANDING[standing], label: codeLabel(standing) }
}

export const SETTLEMENT_OVERDUE_BADGE: StatusBadgeSpec = { tone: 'bad', icon: Clock, label: codeLabel('OVERDUE') }
