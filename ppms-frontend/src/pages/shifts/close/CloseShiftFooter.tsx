import { Check, AlertTriangle } from 'lucide-react'
import { useTranslation } from 'react-i18next'

interface Props {
  readingsComplete: boolean
  paymentTouched: boolean
  isBalanced: boolean
  reasonFilled: boolean
  isHandover?: boolean
  incomingChosen?: boolean
  onCancel: () => void
}

const WAITING_BUTTON = 'ui-btn w-full whitespace-normal bg-surface-sunken text-ink border-control cursor-not-allowed disabled:opacity-100'

export function CloseShiftFooter({
  readingsComplete, paymentTouched, isBalanced, reasonFilled, isHandover = false, incomingChosen = true, onCancel,
}: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  return (
    <>
      <button type="button" onClick={onCancel}
        className="ui-btn ui-btn-secondary sm:flex-none">
        {t('common:actions.cancel')}
      </button>
      <div className="sm:flex-1 min-w-0">
        {!incomingChosen ? (
          <button type="submit" disabled className={WAITING_BUTTON}>
            {t('close.footer.chooseIncoming')}
          </button>
        ) : !readingsComplete || !paymentTouched ? (
          <button type="submit" disabled className={WAITING_BUTTON}>
            {t('close.footer.fillReadings')}
          </button>
        ) : isBalanced ? (
          <button type="submit"
            className="ui-btn w-full bg-ok hover:opacity-90 text-on-primary">
            <span className="inline-flex items-center gap-1.5"><Check size={14} strokeWidth={2.5} />{isHandover ? t('close.footer.reviewHandoverBalanced') : t('close.footer.reviewCloseBalanced')}</span>
          </button>
        ) : reasonFilled ? (
          <button type="submit"
            className="ui-btn w-full bg-warn hover:bg-warn-strong text-on-primary">
            <span className="inline-flex items-center gap-1.5"><AlertTriangle size={14} strokeWidth={2} />{isHandover ? t('close.footer.reviewHandoverDiscrepancy') : t('close.footer.reviewCloseDiscrepancy')}</span>
          </button>
        ) : (
          <button type="submit" disabled
            className="ui-btn w-full whitespace-normal bg-warn-bg text-warn-strong cursor-not-allowed border-2 border-warn border-dashed disabled:opacity-100">
            {t('close.footer.addReason')}
          </button>
        )}
      </div>
    </>
  )
}
