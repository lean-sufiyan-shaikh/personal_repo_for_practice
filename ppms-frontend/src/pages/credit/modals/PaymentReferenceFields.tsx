import { useTranslation } from 'react-i18next'
import type { CreditPaymentMode } from '../../../api/creditApi'
import { FormField } from '../../../components/FormField'
import { needsReference } from './paymentReference'
import type { PaymentReference } from './paymentReference'

interface PaymentReferenceFieldsProps {
  mode: CreditPaymentMode
  value: PaymentReference
  onChange: (value: PaymentReference) => void
}

/** The reference of a UPI or bank transfer payment, or the number, bank and date of a cheque. */
export function PaymentReferenceFields({ mode, value, onChange }: PaymentReferenceFieldsProps) {
  const { t } = useTranslation(['credit', 'common'])
  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>
  const change = (field: keyof PaymentReference, text: string) => onChange({ ...value, [field]: text })

  if (mode !== 'CHEQUE') {
    return (
      <FormField
        label={needsReference(mode) ? t('recordPayment.reference') : <>{t('recordPayment.reference')} {optional}</>}
        help={t('recordPayment.referenceHelp')}
      >
        <input type="text" value={value.referenceNo} maxLength={100} autoCapitalize="characters" spellCheck={false}
          onChange={(e) => change('referenceNo', e.target.value)} />
      </FormField>
    )
  }
  return (
    <>
      <FormField label={t('recordPayment.chequeNumber')}>
        <input type="text" inputMode="numeric" value={value.referenceNo} maxLength={100} spellCheck={false}
          onChange={(e) => change('referenceNo', e.target.value)} />
      </FormField>
      <FormField label={<>{t('recordPayment.chequeBank')} {optional}</>}>
        <input type="text" value={value.chequeBank} maxLength={100} onChange={(e) => change('chequeBank', e.target.value)} />
      </FormField>
      <FormField label={<>{t('recordPayment.chequeDate')} {optional}</>} help={t('recordPayment.chequeDateHelp')}>
        <input type="date" value={value.chequeDate} onChange={(e) => change('chequeDate', e.target.value)} />
      </FormField>
    </>
  )
}

/** The server's possible-duplicate warning, with the way to record the payment anyway. */
export function PossibleDuplicateWarning({ message, isPending, onConfirm }: { message: string; isPending: boolean; onConfirm: () => void }) {
  const { t } = useTranslation('credit')
  return (
    <div role="alert" className="ui-alert ui-alert-warning space-y-2">
      <p className="font-semibold">{t('recordPayment.duplicateTitle')}</p>
      <p>{message}</p>
      <button type="button" onClick={onConfirm} disabled={isPending} className="ui-btn ui-btn-secondary ui-btn-compact">
        {t('recordPayment.recordAnyway')}
      </button>
    </div>
  )
}
