import { ArrowDown, ArrowUp, Lock, Wallet } from 'lucide-react'
import type { CashEventType } from '../../api/cashApi'
import type { StatusBadgeSpec } from '../../components/statusBadges'

export const EVENT_TYPES: { value: CashEventType; label: string }[] = [
  { value: 'OPENING_BALANCE', label: 'Opening Balance' },
  { value: 'CASH_IN',         label: 'Cash In' },
  { value: 'CASH_OUT',        label: 'Cash Out' },
  { value: 'CLOSING_BALANCE', label: 'Closing Balance' },
]

export const EVENT_BADGES: Record<CashEventType, Omit<StatusBadgeSpec, 'label'>> = {
  OPENING_BALANCE: { tone: 'info', icon: Wallet },
  CASH_IN:         { tone: 'ok', icon: ArrowDown },
  CASH_OUT:        { tone: 'bad', icon: ArrowUp },
  CLOSING_BALANCE: { tone: 'neutral', icon: Lock },
}

export { formatCurrency as fmtAmt } from '../../utils/format'

export { formatIstDate as fmtDate } from '../../utils/date'
