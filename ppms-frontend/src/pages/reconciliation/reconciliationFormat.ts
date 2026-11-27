import { AlertTriangle, Check, EyeOff, Lightbulb } from 'lucide-react'
import type { MatchedRecord, StatementLine, StatementLineStatus, UpdateStatementLineRequest } from '../../api/bankReconciliationApi'
import type { StatusBadgeSpec } from '../../components/statusBadges'
import { translateKey } from '../../i18n'
import { codeLabel } from '../../constants/labels'
import { toPaise } from '../../utils/money'

export const LINE_STATUSES: StatementLineStatus[] = ['UNMATCHED', 'SUGGESTED', 'MATCHED', 'IGNORED']

const LINE_STATUS_LOOK: Record<StatementLineStatus, Omit<StatusBadgeSpec, 'label'>> = {
  UNMATCHED: { tone: 'warn', icon: AlertTriangle },
  SUGGESTED: { tone: 'info', icon: Lightbulb },
  MATCHED: { tone: 'ok', icon: Check },
  IGNORED: { tone: 'neutral', icon: EyeOff },
}

export function lineStatusLabel(status: StatementLineStatus): string {
  return translateKey(`reconciliation:status.${status}`)
}

export function lineStatusBadge(status: StatementLineStatus): StatusBadgeSpec {
  return { ...LINE_STATUS_LOOK[status], label: lineStatusLabel(status) }
}

export function recordTypeLabel(record: MatchedRecord): string {
  return codeLabel(record.type)
}

/** Money into the account is a credit line, money out a debit line; a line carries one or the other. */
export function lineAmount(line: StatementLine): { amount: number; isCredit: boolean } {
  const credit = line.creditAmount ?? 0
  return credit > 0 ? { amount: credit, isCredit: true } : { amount: line.debitAmount ?? 0, isCredit: false }
}

export function amountsDiffer(line: StatementLine, record: MatchedRecord): boolean {
  return record.amount !== null && toPaise(record.amount) !== toPaise(lineAmount(line).amount)
}

/** The one link field the server expects for this kind of record. */
export function matchLinkFor(record: MatchedRecord): Partial<UpdateStatementLineRequest> {
  switch (record.type) {
    case 'SETTLEMENT':       return { matchedSettlementId: record.id }
    case 'DEPOSIT':          return { matchedDepositId: record.id }
    case 'CREDIT_PAYMENT':   return { matchedPaymentId: record.id }
    case 'SUPPLIER_PAYMENT': return { matchedSupplierPaymentId: record.id }
    case 'SHIFT':            return { matchedShiftId: record.id }
    case 'ANCILLARY_SALE':   return { matchedSaleId: record.id }
  }
}
