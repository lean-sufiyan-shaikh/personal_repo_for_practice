import { useTranslation } from 'react-i18next'
import type { PettyCashCount, PettyCashSummary } from '../../../api/pettyCashApi'
import { MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { formatCurrency } from '../../../utils/format'
import { formatIstDate, formatIstDateTime } from '../../../utils/date'

function LatestCount({ count }: { count: PettyCashCount }) {
  const { t } = useTranslation('cash')
  const difference = count.varianceAmount
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-semibold text-ink">{t('petty.latestCount')}</span>
        {difference === 0 ? (
          <StatusBadge tone="ok" label={t('safe.matches')} />
        ) : (
          <StatusBadge tone={difference < 0 ? 'bad' : 'warn'}
            label={t(difference < 0 ? 'petty.short' : 'petty.over', { amount: formatCurrency(Math.abs(difference)) })} />
        )}
      </div>
      <p className="text-sm text-ink-2">
        {t('petty.countedAgainst', { date: formatIstDate(count.countForDate), counted: formatCurrency(count.countedAmount),
          expected: formatCurrency(count.expectedAmount) })}
      </p>
      <p className="text-xs text-ink-3">{t('safe.countedBy', { name: count.countedByName, time: formatIstDateTime(count.countedAt) })}</p>
      {count.beyondTolerance && (
        <p className="ui-alert ui-alert-warning px-3 py-2 text-xs">
          {t('safe.beyondTolerance', { amount: formatCurrency(count.toleranceAmount) })}
        </p>
      )}
    </div>
  )
}

/** What the petty cash ledger says is in the box, its float and what tops it back up, and the last count. */
export function PettyCashSummaryCard({ summary }: { summary: PettyCashSummary }) {
  const { t } = useTranslation('cash')
  return (
    <div className="ui-card border-l-4 border-l-primary grid gap-4 sm:grid-cols-2">
      <div>
        <p className="text-sm text-ink-2">{t('petty.balance')}</p>
        <MoneyText value={summary.balance} className="mt-1 block text-3xl font-bold text-ink" />
        <p className="mt-1 text-xs text-ink-3">{t('petty.float', { amount: formatCurrency(summary.floatAmount) })}</p>
        {summary.suggestedTopUp > 0 && (
          <p className="mt-1 text-xs text-warn">{t('petty.suggestedTopUp', { amount: formatCurrency(summary.suggestedTopUp) })}</p>
        )}
        <p className="mt-2 text-xs text-ink-3">{t('safe.tolerance', { amount: formatCurrency(summary.countTolerance) })}</p>
      </div>
      {summary.latestCount
        ? <LatestCount count={summary.latestCount} />
        : <p className="text-sm text-ink-3">{t('petty.noCount')}</p>}
    </div>
  )
}
