import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../api/cashApi'
import type { CashEvent } from '../../api/cashApi'
import { MoneyText } from '../../components/AmountText'
import { FormField } from '../../components/FormField'
import { LiveFigures } from '../../components/LiveFigures'
import { LoadingButton } from '../../components/LoadingButton'
import { Modal } from '../../components/Modal'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { fmtDate } from './cashFormat'

/** Cancels a movement typed by hand with an opposite entry on its day; the reason is required and kept in the audit log. */
export function ReverseCashEventDialog({ pumpId, event, onClose }: { pumpId: number; event: CashEvent; onClose: () => void }) {
  const { t } = useTranslation(['cash', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [reason, setReason] = useState('')
  const [missingReason, setMissingReason] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (text: string) => cashApi.reverseCashEvent(pumpId, event.id, text),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cashEvents', pumpId] })
      addToast(t('reverse.done'), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('reverse.failed'))),
  })

  const submit = (formEvent: FormEvent) => {
    formEvent.preventDefault()
    if (!reason.trim()) {
      setMissingReason(true)
      return
    }
    setError(null)
    mutation.mutate(reason.trim())
  }

  return (
    <Modal
      title={t('reverse.title')}
      subtitle={t('reverse.subtitle')}
      tone="warning"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={submit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')} className="ui-btn-danger">
            {t('reverse.confirm')}
          </LoadingButton>
        </>
      }
    >
      <LiveFigures figures={[
        { label: t(`eventTypes.${event.eventType}`), value: <MoneyText value={event.amount} /> },
        { label: t('reverse.date'), value: fmtDate(event.eventDate) },
        { label: t('reverse.description'), value: event.description },
      ]} />
      <FormField label={t('reverse.reason')} error={missingReason ? t('reverse.reasonMissing') : undefined}>
        <textarea
          value={reason}
          onChange={(changeEvent) => { setReason(changeEvent.target.value); if (changeEvent.target.value.trim()) setMissingReason(false) }}
          rows={3}
          maxLength={400}
          aria-required="true"
          className="resize-none"
          data-autofocus
        />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
