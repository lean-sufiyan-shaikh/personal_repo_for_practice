import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import { PageSection } from '../../../components/layout/PageSection'
import { formatCurrency } from '../../../utils/format'
import { roundToPaise } from '../../../utils/money'
import { CashDropCard } from '../drops/CashDropCard'
import { RejectDropDialog } from '../drops/RejectDropDialog'
import { useReviewCashDrop } from '../drops/useReviewCashDrop'

/** Drops operators have put in the safe that a second person still has to count and confirm; hidden when none. */
export function PendingDropsSection({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('cash')
  const review = useReviewCashDrop(pumpId)
  const { data: drops = [] } = useQuery({
    queryKey: ['pendingCashDrops', pumpId],
    queryFn: () => cashApi.getPendingDrops(pumpId),
    refetchInterval: 60_000,
  })

  if (drops.length === 0) return null
  const total = roundToPaise(drops.reduce((sum, drop) => sum + drop.amount, 0))

  return (
    <PageSection
      title={t('safe.pending.title')}
      titleMeta={t('safe.pending.summary', { count: drops.length, amount: formatCurrency(total) })}
      description={t('safe.pending.hint')}
      flush
    >
      {review.error && !review.rejecting && <p role="alert" className="ui-alert ui-alert-danger mx-4 mb-2">{review.error}</p>}
      <ul className="ui-record-list">
        {drops.map((drop) => <CashDropCard key={drop.id} drop={drop} review={review} showOperator />)}
      </ul>
      <RejectDropDialog review={review} />
    </PageSection>
  )
}
