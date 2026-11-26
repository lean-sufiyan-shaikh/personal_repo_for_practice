import { AlertTriangle, Check, Minus, TrendingUp, type LucideIcon } from 'lucide-react'
import type { TFunction } from 'i18next'
import type { ReconciliationPeriod, TankReconciliation } from '../../../api/inventoryApi'
import type { StatusTone } from '../../../components/StatusBadge'
import { formatDecimal, formatWithSign } from '../../../utils/format'
import { formatIstDate } from '../../../utils/date'

export const MAX_RANGE_DAYS = 92

export interface Badge {
  tone: StatusTone
  icon: LucideIcon
  label: string
}

/** Signed percentage such as −1.20%; a dash when nothing was sold. */
export function signedPercent(t: TFunction<'inventory'>, value: number | null): string {
  return value == null ? '—' : t('reconciliation.percent', { value: formatWithSign(value, formatDecimal) })
}

export function summaryBadge(t: TFunction<'inventory'>, tank: TankReconciliation): Badge {
  const limit = formatDecimal(tank.lossAlertPercent)
  switch (tank.summary.status) {
    case 'LOSS_ABOVE_LIMIT': return { tone: 'bad', icon: AlertTriangle, label: t('reconciliation.statusLossAboveLimit', { limit }) }
    case 'GAIN_ABOVE_LIMIT': return { tone: 'warn', icon: TrendingUp, label: t('reconciliation.statusGainAboveLimit', { limit }) }
    case 'WITHIN_LIMITS':    return { tone: 'ok', icon: Check, label: t('reconciliation.statusWithinLimits') }
    case 'NO_DATA':          return { tone: 'neutral', icon: Minus, label: t('reconciliation.statusNoData') }
  }
}

export function periodBadge(t: TFunction<'inventory'>, period: ReconciliationPeriod): Badge {
  if (period.status === 'NEEDS_OPENING_DIP') return { tone: 'info', icon: Minus, label: t('reconciliation.needsOpeningDip') }
  return period.withinTolerance
    ? { tone: 'ok', icon: Check, label: t('reconciliation.withinTolerance') }
    : { tone: 'bad', icon: AlertTriangle, label: t('reconciliation.outsideTolerance') }
}

export function periodLabel(period: ReconciliationPeriod): string {
  const end = formatIstDate(period.periodEnd, { day: 'numeric', month: 'short' })
  if (period.days <= 1) return end
  return `${formatIstDate(period.periodStart, { day: 'numeric', month: 'short' })} – ${end}`
}
