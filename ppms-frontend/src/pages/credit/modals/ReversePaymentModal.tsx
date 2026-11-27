import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditTransaction, ReversePaymentRequest } from '../../../api/creditApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { LiveFigures } from '../../../components/LiveFigures'
import { MoneyText } from '../../../components/AmountText'
import { NumericInput } from '../../../components/NumericInput'
import { parseApiError } from '../../../utils/apiError'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { formatIstDate } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'
import { roundToPaise } from '../../../utils/money'

interface ReversePaymentModalProps {
  pumpId: number
  payment: CreditTransaction
  onReversed: () => void
  onClose: () => void
}

const PAISE_AMOUNT = /^\d+(\.\d{1,2})?$/

/** Takes an approved payment back off the account today, with a reason and an optional bounce or bank charge. */
export function ReversePaymentModal({ pumpId, payment, onReversed, onClose }: ReversePaymentModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | undefined>()
  const [bounceCharge, setBounceCharge] = useState('')
  const [chargeError, setChargeError] = useState<string | undefined>()
  const [serverError, setServerError] = useState<string | null>(null)
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(null)

  const chargeText = bounceCharge.trim()
  const chargeValue = chargeText === '' ? null : Number(chargeText)
  const chargeIsValid = chargeValue === null || (PAISE_AMOUNT.test(chargeText) && chargeValue > 0)
  const addedBack = roundToPaise(payment.amount + (chargeIsValid && chargeValue !== null ? chargeValue : 0))

  const mutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: ReversePaymentRequest; idempotencyKey: string }) =>
      creditApi.reversePayment(pumpId, payment.referenceId, request, idempotencyKey),
    onSuccess: () => {
      onReversed()
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('reversePayment.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const reasonMissing = reason.trim() === ''
    setReasonError(reasonMissing ? t('reversePayment.reasonRequired') : undefined)
    setChargeError(chargeIsValid ? undefined : t('reversePayment.invalidCharge'))
    if (reasonMissing || !chargeIsValid) return
    const request: ReversePaymentRequest = {
      reason: reason.trim(),
      bounceCharge: chargeValue === null ? undefined : roundToPaise(chargeValue),
    }
    const attempt = attemptFor(submitAttempt, request)
    setSubmitAttempt(attempt)
    mutation.mutate({ request, idempotencyKey: attempt.key })
  }

  return (
    <Modal
      title={t('reversePayment.title')}
      subtitle={`${formatCurrency(payment.amount)} · ${formatIstDate(payment.businessDate, { day: '2-digit', month: 'short', year: 'numeric' })}`}
      tone="warning"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('reversePayment.keep')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('reversePayment.reversing')}
            className="ui-btn-danger">
            {t('reversePayment.confirm')}
          </LoadingButton>
        </>
      }
    >
      <LiveFigures figures={[
        { label: t('reversePayment.payment'), value: <MoneyText value={payment.amount} /> },
        { label: t('reversePayment.bounceCharge'), value: <MoneyText value={chargeIsValid ? chargeValue : null} /> },
        { label: t('reversePayment.addedBack'), value: <MoneyText value={addedBack} />, isResult: true },
      ]} />
      <p className="text-sm text-ink-2">{t('reversePayment.explanation')}</p>
      <FormField label={t('reversePayment.reason')} error={reasonError}>
        <textarea value={reason} placeholder={t('reversePayment.reasonPlaceholder')}
          onChange={(e) => { setReason(e.target.value); setReasonError(undefined) }}
          rows={3} maxLength={500} className="resize-none" data-autofocus />
      </FormField>
      <FormField
        label={<>{t('reversePayment.bounceCharge')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}
        help={t('reversePayment.bounceChargeHelp')}
        error={chargeError}
      >
        <NumericInput unit="₹" min="0.01" step="0.01" value={bounceCharge}
          onChange={(e) => { setBounceCharge(e.target.value); setChargeError(undefined) }} />
      </FormField>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
