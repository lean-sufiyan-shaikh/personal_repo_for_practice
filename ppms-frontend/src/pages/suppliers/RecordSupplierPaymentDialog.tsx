import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { SUPPLIER_PAYMENT_MODES, supplierApi } from '../../api/supplierApi'
import type { RecordSupplierPaymentRequest, Supplier, SupplierPaymentMode } from '../../api/supplierApi'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { Modal } from '../../components/Modal'
import { NumericInput } from '../../components/NumericInput'
import { StillNeeded } from '../../components/StillNeeded'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { localDateInputValue } from '../../utils/date'
import { formatCurrency } from '../../utils/format'
import { attemptFor } from '../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../utils/idempotencyKey'
import { roundToPaise } from '../../utils/money'

interface Props {
  pumpId: number
  supplier: Supplier
  outstanding: number
  onClose: () => void
}

/** Records a payment to a supplier; a resend after a timeout carries the same key, so it is recorded once. */
export function RecordSupplierPaymentDialog({ pumpId, supplier, outstanding, onClose }: Props) {
  const { t } = useTranslation(['suppliers', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [amount, setAmount] = useState('')
  const [paymentDate, setPaymentDate] = useState(() => localDateInputValue())
  const [paymentMode, setPaymentMode] = useState<SupplierPaymentMode>('BANK_TRANSFER')
  const [referenceNo, setReferenceNo] = useState('')
  const [notes, setNotes] = useState('')
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(null)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: RecordSupplierPaymentRequest; idempotencyKey: string }) =>
      supplierApi.recordPayment(pumpId, request, idempotencyKey),
    onSuccess: payment => {
      for (const key of ['supplier-balances', 'supplier-ledger', 'supplier-payments', 'safe', 'cashEvents']) {
        queryClient.invalidateQueries({ queryKey: [key, pumpId] })
      }
      addToast(t('payment.saved', { amount: formatCurrency(payment.amount), name: supplier.name }), 'success')
      onClose()
    },
    onError: err => setError(parseApiError(err, t('payment.failed'))),
  })

  const amountValue = Number(amount)
  const hasAmount = amount.trim() !== '' && Number.isFinite(amountValue) && amountValue > 0
  const stillNeeded = [
    !hasAmount && t('payment.amount'),
    paymentDate === '' && t('payment.date'),
  ].filter((item): item is string => typeof item === 'string')

  const submit = () => {
    if (stillNeeded.length > 0) return
    setError(null)
    const request: RecordSupplierPaymentRequest = {
      supplierId: supplier.id,
      amount: roundToPaise(amountValue),
      paymentDate,
      paymentMode,
      referenceNo: referenceNo.trim() || undefined,
      notes: notes.trim() || undefined,
    }
    const attempt = attemptFor(submitAttempt, request)
    setSubmitAttempt(attempt)
    mutation.mutate({ request, idempotencyKey: attempt.key })
  }

  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>

  return (
    <Modal
      title={t('payment.title')}
      subtitle={t('payment.subtitle', { name: supplier.name, amount: formatCurrency(outstanding) })}
      tone="info"
      size="md-wide"
      fullScreenOnPhone
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="button" onClick={submit} disabled={stillNeeded.length > 0} isLoading={mutation.isPending}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          loadingLabel={t('payment.saving')} className="ui-btn-primary min-h-11">
          {t('payment.record')}
        </LoadingButton>
      </>}
    >
      <FormGrid>
        <FormField label={t('payment.amount')}>
          <NumericInput unit="₹" min="0.01" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} data-autofocus />
        </FormField>
        <FormField label={t('payment.date')}>
          <input type="date" value={paymentDate} max={localDateInputValue()} onChange={e => setPaymentDate(e.target.value)} />
        </FormField>
        <FormField label={t('payment.mode')}>
          <select value={paymentMode} onChange={e => setPaymentMode(e.target.value as SupplierPaymentMode)}>
            {SUPPLIER_PAYMENT_MODES.map(mode => (
              <option key={mode} value={mode}>{t(`common:paymentMethods.${mode}`)}</option>
            ))}
          </select>
        </FormField>
        <FormField label={<>{paymentMode === 'CHEQUE' ? t('payment.chequeNumber') : t('payment.reference')} {optional}</>}>
          <input type="text" value={referenceNo} maxLength={100} onChange={e => setReferenceNo(e.target.value)} />
        </FormField>
        <FormGridFullRow>
          <FormField label={<>{t('common:fields.notes')} {optional}</>}>
            <textarea value={notes} onChange={e => setNotes(e.target.value)} rows={2} maxLength={1000} className="resize-none" />
          </FormField>
        </FormGridFullRow>
      </FormGrid>

      {paymentMode === 'CASH' && <p className="ui-alert ui-alert-warning">{t('payment.cashNote')}</p>}
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
