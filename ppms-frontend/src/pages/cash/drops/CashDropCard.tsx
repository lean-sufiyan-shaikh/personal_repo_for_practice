import { useTranslation } from 'react-i18next'
import type { CashDrop, CashDropStatus } from '../../../api/cashApi'
import { MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { LoadingButton } from '../../../components/LoadingButton'
import { formatIstDateTime } from '../../../utils/date'
import type { CashDropReview } from './useReviewCashDrop'

const STATUS_TONE: Record<CashDropStatus, StatusTone> = { PENDING: 'warn', CONFIRMED: 'ok', REJECTED: 'bad' }

interface Props {
  drop: CashDrop
  /** Shown with Confirm and Reject buttons when the viewer may review a waiting drop. */
  review?: CashDropReview
  showOperator?: boolean
}

/** One cash drop as a phone card: amount, status, who and when, and the review buttons while it waits. */
export function CashDropCard({ drop, review, showOperator = false }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const time = formatIstDateTime(drop.recordedAt, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: true })
  const canReview = review !== undefined && drop.status === 'PENDING'

  return (
    <li className="ui-record-row flex-wrap gap-y-2">
      <div className="min-w-0 ui-record-row__main">
        <div className="flex flex-wrap items-center gap-2">
          <MoneyText value={drop.amount} className="text-base font-semibold text-ink" />
          <StatusBadge tone={STATUS_TONE[drop.status]} label={t(`drops.status.${drop.status}`)} />
        </div>
        <p className="mt-0.5 text-xs text-ink-3">
          {showOperator ? t('drops.byOperatorAt', { operator: drop.operatorName, time }) : time}
          {drop.status !== 'PENDING' && drop.reviewedByName && ` · ${t(`drops.reviewedBy.${drop.status}`, { name: drop.reviewedByName })}`}
        </p>
        {drop.rejectionReason && <p className="mt-0.5 text-xs text-bad">{drop.rejectionReason}</p>}
      </div>
      {canReview && (
        <div className="flex w-full gap-2 sm:w-auto">
          <button type="button" onClick={() => review.startReject(drop)} className="ui-btn ui-btn-secondary min-h-11 flex-1 sm:flex-none">
            {t('common:actions.reject')}
          </button>
          <LoadingButton type="button" onClick={() => review.confirm(drop)} isLoading={review.confirmingId === drop.id}
            disabled={review.confirmingId !== null} loadingLabel={t('drops.confirming')}
            className="ui-btn-primary min-h-11 flex-1 sm:flex-none">
            {t('drops.confirm')}
          </LoadingButton>
        </div>
      )}
    </li>
  )
}
