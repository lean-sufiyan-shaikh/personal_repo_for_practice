import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { supplierApi } from '../../api/supplierApi'
import type { SupplierPayment } from '../../api/supplierApi'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { Modal } from '../../components/Modal'
import { StillNeeded } from '../../components/StillNeeded'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { formatCurrency } from '../../utils/format'
import { formatSupplierDate } from './supplierFormat'

interface Props {
  pumpId: number
  payment: SupplierPayment
  onClose: () => void
}

/** Voids a supplier payment with a reason; a cash payment's money goes back into the safe. */
export function VoidSupplierPaymentDialog({ pumpId, payment, onClose }: Props) {
  const { t } = useTranslation(['suppliers', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (trimmedReason: string) => supplierApi.voidPayment(pumpId, payment.id, trimmedReason),
    onSuccess: () => {
      for (const key of ['supplier-balances', 'supplier-ledger', 'supplier-payments', 'safe', 'cashEvents']) {
        queryClient.invalidateQueries({ queryKey: [key, pumpId] })
      }
      addToast(t('void.voided'), 'success')
      onClose()
    },
    onError: err => setError(parseApiError(err, t('void.failed'))),
  })

  const stillNeeded = reason.trim() ? [] : [t('void.needReason')]

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (stillNeeded.length > 0) return
    setError(null)
    mutation.mutate(reason.trim())
  }

  return (
    <Modal
      tone="warning"
      title={t('void.title')}
      subtitle={`${payment.supplierName} · ${formatSupplierDate(payment.paymentDate)} · ${formatCurrency(payment.amount)}`}
      size="md-wide"
      onClose={onClose}
      canClose={!mutation.isPending}
      closeOnBackdropClick
      onSubmit={submit}
      bodyClassName="flex flex-col gap-4"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('void.voiding')}
          disabled={stillNeeded.length > 0}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          className="ui-btn-danger">
          {t('void.title')}
        </LoadingButton>
      </>}
    >
      <p className="text-sm text-ink-2">
        {payment.paymentMode === 'CASH' ? t('void.explanationCash') : t('void.explanation')}
      </p>
      <FormField label={t('common:fields.reason')}>
        <input type="text" value={reason} maxLength={500} onChange={e => setReason(e.target.value)} data-autofocus />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
