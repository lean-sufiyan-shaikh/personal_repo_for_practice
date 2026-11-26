import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { CashDrop } from '../../../api/cashApi'
import { MoneyText } from '../../../components/AmountText'
import { CashDropCard } from '../../cash/drops/CashDropCard'
import { RejectDropDialog } from '../../cash/drops/RejectDropDialog'
import { useReviewCashDrop } from '../../cash/drops/useReviewCashDrop'

interface Props {
  pumpId: number
  drops: CashDrop[]
  confirmedTotal: number
}

/**
 * The shift's cash drops at close. Confirmed drops are already in the safe; a drop still waiting must be confirmed
 * or rejected here first, because until then nobody knows whether that cash is in the safe or still in the bag.
 */
export function CashDropsAtClose({ pumpId, drops, confirmedTotal }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const headingId = useId()
  const review = useReviewCashDrop(pumpId)
  const pending = drops.filter((drop) => drop.status === 'PENDING')
  if (drops.length === 0) return null

  return (
    <section aria-labelledby={headingId} className="rounded-card border border-border">
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-border px-3 py-2">
        <h3 id={headingId} className="text-sm font-semibold text-ink">{t('close.drops.title')}</h3>
        <span className="text-sm text-ink-2">
          {t('close.drops.confirmedTotal')} <MoneyText value={confirmedTotal} className="font-semibold text-ink" />
        </span>
      </div>
      {pending.length > 0 && (
        <p role="status" className="ui-alert ui-alert-warning m-3 text-sm">{t('close.drops.pendingNotice', { count: pending.length })}</p>
      )}
      <ul className="ui-record-list">
        {drops.map((drop) => <CashDropCard key={drop.id} drop={drop} review={review} />)}
      </ul>
      {review.error && !review.rejecting && <p role="alert" className="ui-alert ui-alert-danger m-3">{review.error}</p>}
      <RejectDropDialog review={review} />
    </section>
  )
}
