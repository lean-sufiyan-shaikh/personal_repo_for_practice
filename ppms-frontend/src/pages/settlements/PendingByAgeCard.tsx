import { AlertTriangle, Clock } from 'lucide-react'
import type { PendingSettlementRange } from '../../api/paymentSettlementApi'
import { SkeletonRows } from '../../components/Skeleton'
import { parseApiError } from '../../utils/apiError'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { TYPE_LABELS } from './settlementFormat'
import { ageLabel, collectionDaysLabel } from './settlementAging'

/** Money collected by UPI / Card / Fleet Card that has not reached the bank, oldest first, flagged once past T+2. */
export function PendingByAgeCard({ ranges, isLoading, error }: {
  ranges: PendingSettlementRange[] | undefined
  isLoading: boolean
  error: unknown
}) {
  const overdueCount = ranges?.filter((range) => range.overdue).length ?? 0
  return (
    <section className="ui-card p-0 overflow-hidden mb-5" aria-labelledby="pending-by-age-title">
      <div className="ui-toolbar">
        <h2 id="pending-by-age-title" className="ui-toolbar-title">Waiting for the bank</h2>
        {overdueCount > 0 && (
          <StatusBadge tone="warn" icon={AlertTriangle} label={`${overdueCount} overdue`} />
        )}
        <p className="ui-toolbar-note basis-full">Unsettled collections by collection date. Card and UPI money is due within 2 days (T+2).</p>
      </div>
      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={2} /></div>
      ) : error ? (
        <p role="alert" className="ui-alert ui-alert-danger m-4 text-sm">{parseApiError(error, 'Could not load unsettled collections.')}</p>
      ) : !ranges || ranges.length === 0 ? (
        <p className="ui-empty px-5 py-4">Everything collected has been settled.</p>
      ) : (
        <ul className="divide-y divide-border">
          {ranges.map((range) => (
            <li key={`${range.paymentType}-${range.fromDate}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3 text-sm">
              <span className="font-semibold text-ink">
                {TYPE_LABELS[range.paymentType]} {collectionDaysLabel(range.fromDate, range.toDate)}
              </span>
              <MoneyText value={range.amount} className="text-ink-2" />
              <StatusBadge
                tone={range.overdue ? 'warn' : 'neutral'}
                icon={range.overdue ? AlertTriangle : Clock}
                label={`${ageLabel(range.ageDays)}${range.overdue ? ' · overdue' : ''}`}
                className="ml-auto"
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
