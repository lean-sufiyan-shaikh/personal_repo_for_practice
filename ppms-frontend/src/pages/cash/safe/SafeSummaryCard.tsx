import { useTranslation } from 'react-i18next'
import type { SafeCount, SafeSummary } from '../../../api/cashApi'
import { MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency } from '../../../utils/format'
import { formatIstDateTime } from '../../../utils/date'

function LatestCount({ count }: { count: SafeCount }) {
  const { t } = useTranslation('cash')
  const time = formatIstDateTime(count.countedAt, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: true })
  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-semibold text-ink">{t('safe.latestCount')}</span>
        {count.varianceType === null ? (
          <StatusBadge tone="ok" label={t('safe.matches')} />
        ) : (
          <StatusBadge tone={count.varianceType === 'SHORT' ? 'bad' : 'warn'}
            label={`${codeLabel(count.varianceType)} ${formatCurrency(Math.abs(count.varianceAmount))}`} />
        )}
      </div>
      <p className="text-sm text-ink-2">
        {t('safe.countedAgainst', { counted: formatCurrency(count.countedAmount), expected: formatCurrency(count.expectedAmount) })}
      </p>
      <p className="text-xs text-ink-3">{t('safe.countedBy', { name: count.countedByName, time })}</p>
      {count.beyondTolerance && (
        <p className="ui-alert ui-alert-warning px-3 py-2 text-xs">
          {t('safe.beyondTolerance', { amount: formatCurrency(count.toleranceAmount) })}
        </p>
      )}
    </div>
  )
}

/** What the ledger says is in the safe, the pump's count tolerance and how the last count compared. */
export function SafeSummaryCard({ summary }: { summary: SafeSummary }) {
  const { t } = useTranslation('cash')
  return (
    <div className="ui-card border-l-4 border-l-primary grid gap-4 sm:grid-cols-2">
      <div>
        <p className="text-sm text-ink-2">{t('safe.balance')}</p>
        <MoneyText value={summary.balance} className="mt-1 block text-3xl font-bold text-ink" />
        <p className="mt-1 text-xs text-ink-3">{t('safe.balanceHint')}</p>
        <p className="mt-2 text-xs text-ink-3">{t('safe.tolerance', { amount: formatCurrency(summary.countTolerance) })}</p>
      </div>
      {summary.latestCount
        ? <LatestCount count={summary.latestCount} />
        : <p className="text-sm text-ink-3">{t('safe.noCount')}</p>}
    </div>
  )
}
