import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import type { PaymentPromise } from '../../../api/collectionApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { formatIstDate } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

interface CancelPromiseModalProps {
  pumpId: number
  clientName: string
  promise: PaymentPromise
  onClose: () => void
}

/** Withdraws an open promise with a reason; reminders resume for the account. */
export function CancelPromiseModal({ pumpId, clientName, promise, onClose }: CancelPromiseModalProps) {
  const { t } = useTranslation(['collections', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | undefined>()
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => collectionApi.cancelPromise(pumpId, promise.id, reason.trim()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['collections', pumpId] })
      addToast(t('promise.cancelled'), 'success')
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('promise.cancelFailed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    if (!reason.trim()) { setReasonError(t('promise.cancelReasonRequired')); return }
    setReasonError(undefined)
    mutation.mutate()
  }

  return (
    <Modal
      title={t('promise.cancelTitle')}
      subtitle={`${clientName} · ${t('promise.summary', {
        amount: formatCurrency(promise.amount), date: formatIstDate(promise.promisedDate),
      })}`}
      tone="warning"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('promise.keepPromise')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('promise.cancelling')}
            className="ui-btn-danger">
            {t('promise.cancel')}
          </LoadingButton>
        </>
      }
    >
      <p className="text-sm text-ink-2">{t('promise.cancelHint')}</p>
      <FormField label={t('promise.cancelReason')} error={reasonError}>
        <textarea value={reason} onChange={(e) => { setReason(e.target.value); setReasonError(undefined) }}
          rows={3} maxLength={500} className="resize-none" data-autofocus />
      </FormField>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
