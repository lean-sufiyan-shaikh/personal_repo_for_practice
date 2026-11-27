import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { FormGrid } from '../../../components/layout/FormGrid'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { istDateString } from '../../../utils/date'

interface PromiseModalProps {
  pumpId: number
  clientId: number
  clientName: string
  /** Starts the amount at what is owed. */
  outstanding: number
  onClose: () => void
}

/** Records "will pay ₹X by this date"; reminders pause until then. */
export function PromiseModal({ pumpId, clientId, clientName, outstanding, onClose }: PromiseModalProps) {
  const { t } = useTranslation(['collections', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const today = istDateString()
  const [amount, setAmount] = useState(outstanding > 0 ? String(outstanding) : '')
  const [promisedDate, setPromisedDate] = useState(today)
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<{ amount?: string; date?: string }>({})
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => collectionApi.recordPromise(pumpId, clientId, {
      amount: Number(amount), promisedDate, note: note.trim() || undefined,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['collections', pumpId] })
      addToast(t('promise.saved'), 'success')
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('promise.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const value = Number(amount)
    const found = {
      amount: amount.trim() !== '' && Number.isFinite(value) && value > 0 ? undefined : t('promise.invalidAmount'),
      date: promisedDate !== '' && promisedDate >= today ? undefined : t('promise.invalidDate'),
    }
    setErrors(found)
    if (found.amount || found.date) return
    mutation.mutate()
  }

  return (
    <Modal
      title={t('promise.title')}
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
            {t('promise.save')}
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
          <input type="date" value={promisedDate} min={today}
            onChange={(e) => { setPromisedDate(e.target.value); setErrors((current) => ({ ...current, date: undefined })) }} />
        </FormField>
      </FormGrid>
      <FormField label={<>{t('promise.note')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>
      <p className="text-sm text-ink-3">{t('promise.pausesHint')}</p>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
