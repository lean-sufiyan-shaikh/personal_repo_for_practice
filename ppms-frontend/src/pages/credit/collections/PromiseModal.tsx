import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import type { PaymentPromise } from '../../../api/collectionApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { FormGrid } from '../../../components/layout/FormGrid'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { addDays, istDateString } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

/** The server refuses a promise further ahead of the day it is recorded or changed. */
const PROMISE_MAX_DAYS_AHEAD = 90

interface PromiseModalProps {
  pumpId: number
  clientId: number
  clientName: string
  /** Starts the amount at what is owed when recording a new promise. */
  outstanding: number
  /** The open promise to change; without it a new promise is recorded. */
  promise?: PaymentPromise
  onClose: () => void
}

/** Records "will pay ₹X by this date", or changes an open promise; reminders pause until then. */
export function PromiseModal({ pumpId, clientId, clientName, outstanding, promise, onClose }: PromiseModalProps) {
  const { t } = useTranslation(['collections', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const today = istDateString()
  const latestDate = addDays(today, PROMISE_MAX_DAYS_AHEAD)
  const isChange = promise != null
  const [amount, setAmount] = useState(promise ? String(promise.amount) : outstanding > 0 ? String(outstanding) : '')
  const [promisedDate, setPromisedDate] = useState(promise?.promisedDate ?? today)
  const [note, setNote] = useState(promise?.note ?? '')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<{ amount?: string; date?: string }>({})
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => {
      const request = { amount: Number(amount), promisedDate, note: note.trim() || undefined }
      return promise
        ? collectionApi.editPromise(pumpId, promise.id, { ...request, reason: reason.trim() || undefined })
        : collectionApi.recordPromise(pumpId, clientId, request)
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['collections', pumpId] })
      addToast(t(isChange ? 'promise.changed' : 'promise.saved'), 'success')
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t(isChange ? 'promise.changeFailed' : 'promise.failed'))),
  })

  const dateError = (): string | undefined => {
    if (promisedDate === '' || promisedDate < today) return t('promise.invalidDate')
    if (promisedDate > latestDate) return t('promise.dateTooFar', { days: PROMISE_MAX_DAYS_AHEAD })
    return undefined
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const value = Number(amount)
    const found = {
      amount: amount.trim() !== '' && Number.isFinite(value) && value > 0 ? undefined : t('promise.invalidAmount'),
      date: dateError(),
    }
    setErrors(found)
    if (found.amount || found.date) return
    mutation.mutate()
  }

  return (
    <Modal
      title={t(isChange ? 'promise.changeTitle' : 'promise.title')}
      subtitle={clientName}
      tone="info"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('promise.saving')} className="ui-btn-primary">
            {t(isChange ? 'promise.saveChanges' : 'promise.save')}
          </LoadingButton>
        </>
      }
    >
      <FormGrid>
        <FormField label={t('promise.amount')} error={errors.amount}>
          <NumericInput unit="₹" min="0.01" step="0.01" value={amount} data-autofocus
            onChange={(e) => { setAmount(e.target.value); setErrors((current) => ({ ...current, amount: undefined })) }} />
        </FormField>
        <FormField label={t('promise.date')} error={errors.date}>
          <input type="date" value={promisedDate} min={today} max={latestDate}
            onChange={(e) => { setPromisedDate(e.target.value); setErrors((current) => ({ ...current, date: undefined })) }} />
        </FormField>
      </FormGrid>
      <p className="text-xs text-ink-3">
        {t('promise.limitsHint', { days: PROMISE_MAX_DAYS_AHEAD, outstanding: formatCurrency(outstanding) })}
      </p>
      <FormField label={<>{t('promise.note')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>
      {isChange && (
        <FormField label={<>{t('promise.changeReason')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} maxLength={500} className="resize-none" />
        </FormField>
      )}
      <p className="text-sm text-ink-3">{t('promise.pausesHint')}</p>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
