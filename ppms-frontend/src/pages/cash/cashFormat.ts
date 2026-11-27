import { ArrowDown, ArrowUp, Landmark, Lock, PackageCheck, Wallet } from 'lucide-react'
import type { CashEvent, CashEventType, ManualCashEventType } from '../../api/cashApi'
import type { StatusBadgeSpec } from '../../components/statusBadges'

/** The types a person records by hand. Labels are cash.json eventTypes.<value>. */
export const EVENT_TYPES: ManualCashEventType[] = ['OPENING_BALANCE', 'CASH_IN', 'CASH_OUT', 'CLOSING_BALANCE']

/** Every type the list can be filtered by, including the ones written by drops and deposits. */
export const ALL_EVENT_TYPES: CashEventType[] = [...EVENT_TYPES, 'CASH_DROP', 'BANK_DEPOSIT']

export const EVENT_BADGES: Record<CashEventType, Omit<StatusBadgeSpec, 'label'>> = {
  OPENING_BALANCE: { tone: 'info', icon: Wallet },
  CASH_IN:         { tone: 'ok', icon: ArrowDown },
  CASH_OUT:        { tone: 'bad', icon: ArrowUp },
  CLOSING_BALANCE: { tone: 'neutral', icon: Lock },
  CASH_DROP:       { tone: 'ok', icon: PackageCheck },
  BANK_DEPOSIT:    { tone: 'info', icon: Landmark },
}

const REVERSIBLE_TYPES = new Set<CashEventType>(['OPENING_BALANCE', 'CASH_IN', 'CASH_OUT'])

/** A movement typed by hand that has not been reversed and is not itself a reversal. */
export function isReversible(event: CashEvent): boolean {
  return event.recordedByHand && event.reversedAt == null && event.reversesEventId == null && REVERSIBLE_TYPES.has(event.eventType)
}

/** Money leaving the safe. */
export function isOutflow(eventType: CashEventType): boolean {
  return eventType === 'CASH_OUT' || eventType === 'BANK_DEPOSIT'
}

export { formatCurrency as fmtAmt } from '../../utils/format'

export { formatIstDate as fmtDate } from '../../utils/date'

/** The safe's balance once this movement is saved; null for the balance events, which do not move money. */
export function balanceAfter(eventType: ManualCashEventType, balance: number, amount: number): number | null {
  if (eventType === 'CASH_IN') return balance + amount
  if (eventType === 'CASH_OUT') return balance - amount
  return null
}
