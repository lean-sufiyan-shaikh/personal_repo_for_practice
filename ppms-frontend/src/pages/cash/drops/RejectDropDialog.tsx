import { useTranslation } from 'react-i18next'
import { RejectReasonDialog } from '../../../components/RejectReasonDialog'
import { formatCurrency } from '../../../utils/format'
import type { CashDropReview } from './useReviewCashDrop'

/** The reason dialog for the drop being rejected, if any. */
export function RejectDropDialog({ review }: { review: CashDropReview }) {
  const { t } = useTranslation('cash')
  if (!review.rejecting) return null
  return (
    <RejectReasonDialog
      title={t('drops.rejectTitle')}
      subtitle={t('drops.rejectSubtitle', { amount: formatCurrency(review.rejecting.amount), operator: review.rejecting.operatorName })}
      isPending={review.isRejecting}
      error={review.error}
      onConfirm={review.reject}
      onClose={review.cancelReject}
    />
  )
}
