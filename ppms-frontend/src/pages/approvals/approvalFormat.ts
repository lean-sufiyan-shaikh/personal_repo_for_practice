import type { ApprovalItem } from '../../api/approvalApi'
import { codeLabel } from '../../constants/labels'
import { translateKey } from '../../i18n'
import { formatCurrency } from '../../utils/format'

export function approvalKey(item: Pick<ApprovalItem, 'kind' | 'id'>) {
  return `${item.kind}-${item.id}`
}

/** How long an item has waited, in the largest whole unit: "3 days", "5 hours", "just now". */
export function waitingFor(submittedAt: string | null, now: number): string {
  if (!submittedAt) return '—'
  const minutes = Math.floor((now - new Date(submittedAt).getTime()) / 60_000)
  if (minutes < 60) return minutes < 1 ? translateKey('approvals:waiting.justNow') : translateKey('approvals:waiting.minutes', { count: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return translateKey('approvals:waiting.hours', { count: hours })
  const days = Math.floor(hours / 24)
  return translateKey('approvals:waiting.days', { count: days })
}

export type KindFilter = 'all' | 'expenses' | 'payments' | 'meter'

export const KIND_FILTERS: KindFilter[] = ['all', 'expenses', 'payments', 'meter']

const KIND_OF_FILTER: Record<Exclude<KindFilter, 'all'>, ApprovalItem['kind']> = {
  expenses: 'EXPENSE',
  payments: 'CREDIT_PAYMENT',
  meter: 'METER_ADJUSTMENT',
}

export function kindFilterOf(value: string | null): KindFilter {
  return value === 'expenses' || value === 'payments' || value === 'meter' ? value : 'all'
}

export function matchesKind(item: ApprovalItem, filter: KindFilter) {
  return filter === 'all' || item.kind === KIND_OF_FILTER[filter]
}

/** An expense's category, a payment's client, or a meter adjustment's nozzle and unbilled litres. */
export function itemName(item: ApprovalItem): string {
  if (item.kind === 'EXPENSE') return codeLabel(item.category ?? '')
  if (item.kind === 'METER_ADJUSTMENT') {
    return translateKey('approvals:table.meterItem', {
      nozzle: item.nozzleLabel ?? '—', fuel: codeLabel(item.category ?? ''), litres: item.litres ?? 0,
    })
  }
  return item.clientName ?? translateKey('approvals:table.clientNumber', { id: item.clientId })
}

const OUTCOME_NAME_KEY: Record<ApprovalItem['kind'], string> = {
  EXPENSE: 'approvals:outcome.expenseName',
  CREDIT_PAYMENT: 'approvals:outcome.paymentName',
  METER_ADJUSTMENT: 'approvals:outcome.meterName',
}

export function outcomeName(item: ApprovalItem): string {
  return translateKey(OUTCOME_NAME_KEY[item.kind], { amount: formatCurrency(item.amount) })
}
