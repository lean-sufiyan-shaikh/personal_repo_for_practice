import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { CreditPaymentMode } from '../../../api/creditApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { MoneyText } from '../../../components/AmountText'
import { parseApiError } from '../../../utils/apiError'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { localDateInputValue } from '../../../utils/date'
import { roundToPaise } from '../../../utils/money'
import { DESK_ACTIONS } from './deskActions'
import type { DeskActionKind, DeskDebit, DeskFormValues } from './deskActions'

const PAYMENT_MODES: CreditPaymentMode[] = ['CASH', 'UPI', 'BANK_TRANSFER', 'CHEQUE', 'OTHER']
const PAISE_AMOUNT = /^\d+(\.\d{1,2})?$/

interface DeskActionModalProps {
  kind: DeskActionKind
  pumpId: number
  clientId: number
  clientName: string
  /** The most the action can take, shown as a guide; the server enforces it. */
  available?: number
  debit?: DeskDebit
  onDone: () => void
  onClose: () => void
}

/** One form for the credit desk actions: credit note, advance refund, deposits, write-off and closing an account. */
export function DeskActionModal({ kind, pumpId, clientId, clientName, available, debit, onDone, onClose }: DeskActionModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const config = DESK_ACTIONS[kind]
  const modeLabelId = useId()
  const [amount, setAmount] = useState('')
  const [paymentMode, setPaymentMode] = useState<CreditPaymentMode>('CASH')
  const [referenceNo, setReferenceNo] = useState('')
  const [reason, setReason] = useState('')
  const [date, setDate] = useState(() => localDateInputValue())
  const [quantity, setQuantity] = useState('')
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [serverError, setServerError] = useState<string | null>(null)
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(null)

  const mutation = useMutation({
    mutationFn: ({ values, key }: { values: DeskFormValues; key: string }) =>
      config.submit(pumpId, clientId, values, debit, key),
    onSuccess: () => {
      onDone()
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('desk.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const amountText = amount.trim()
    const amountMissing = config.amount === 'required' && amountText === ''
    if (amountMissing || (amountText !== '' && (!PAISE_AMOUNT.test(amountText) || Number(amountText) <= 0))) {
      setFieldError(t('desk.invalidAmount'))
      return
    }
    if (config.reason && reason.trim() === '') {
      setFieldError(t('desk.reasonRequired'))
      return
    }
    setFieldError(null)
    const values: DeskFormValues = {
      amount: amountText === '' ? undefined : roundToPaise(Number(amountText)),
      paymentMode, referenceNo, reason, date,
      returnedQuantity: quantity.trim() === '' ? undefined : Number(quantity),
    }
    const attempt = attemptFor(submitAttempt, { kind, values })
    setSubmitAttempt(attempt)
    mutation.mutate({ values, key: attempt.key })
  }

  return (
    <Modal
      title={t(`desk.${kind}.title`)}
      subtitle={clientName}
      tone={config.tone}
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')}
            className={config.tone === 'danger' ? 'ui-btn-danger' : 'ui-btn-primary'}>
            {t(`desk.${kind}.confirm`)}
          </LoadingButton>
        </>
      }
    >
      <p className="text-sm text-ink-2">{t(`desk.${kind}.help`)}</p>
      {available !== undefined && (
        <p className="text-sm text-ink-3">{t('desk.available')} <MoneyText value={available} className="font-semibold text-ink" /></p>
      )}
      {config.amount !== 'none' && (
        <FormField label={config.amount === 'optional' ? t('desk.amountOptional') : t('desk.amount')}>
          <NumericInput unit="₹" min="0.01" step="0.01" value={amount} data-autofocus
            onChange={(e) => { setAmount(e.target.value); setFieldError(null) }} />
        </FormField>
      )}
      {config.quantity && debit && (debit.type === 'SALE' || debit.type === 'COUNTER_SALE') && (
        <FormField label={<>{t('desk.returnedQuantity')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
          <NumericInput min="0.001" step="0.001" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </FormField>
      )}
      {config.paymentMode && (
        <div>
          <span id={modeLabelId} className="ui-label">{t('desk.paymentMode')}</span>
          <SearchableSelect aria-labelledby={modeLabelId} value={paymentMode}
            onChange={v => setPaymentMode(v as CreditPaymentMode)}
            options={PAYMENT_MODES.map(mode => ({ value: mode, label: t(`common:paymentMethods.${mode}`) }))} />
        </div>
      )}
      {config.paymentMode && (
        <FormField label={<>{t('desk.reference')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
          <input type="text" maxLength={100} value={referenceNo} onChange={(e) => setReferenceNo(e.target.value)} />
        </FormField>
      )}
      {config.date && (
        <FormField label={t('desk.date')}>
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </FormField>
      )}
      {config.reason && (
        <FormField label={t('desk.reason')}>
          <textarea value={reason} rows={2} maxLength={500} className="resize-none"
            onChange={(e) => { setReason(e.target.value); setFieldError(null) }} />
        </FormField>
      )}
      {fieldError && <p role="alert" className="ui-alert ui-alert-danger">{fieldError}</p>}
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
