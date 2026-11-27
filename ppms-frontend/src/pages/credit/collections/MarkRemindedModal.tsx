import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import type { ReminderMethod } from '../../../api/collectionApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'

const METHODS: ReminderMethod[] = ['CALL', 'IN_PERSON', 'MESSAGE_COPIED']

interface MarkRemindedModalProps {
  pumpId: number
  clientId: number
  clientName: string
  /** The method to start with, e.g. MESSAGE_COPIED right after the message was copied. */
  initialMethod?: ReminderMethod
  onClose: () => void
}

export function MarkRemindedModal({ pumpId, clientId, clientName, initialMethod = 'CALL', onClose }: MarkRemindedModalProps) {
  const { t } = useTranslation(['collections', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [method, setMethod] = useState<ReminderMethod>(initialMethod)
  const [note, setNote] = useState('')
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => collectionApi.recordReminder(pumpId, clientId, method, note.trim() || undefined),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['collections', pumpId] })
      addToast(t('markDialog.saved'), 'success')
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('markDialog.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title={t('markDialog.title')}
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
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('markDialog.saving')} className="ui-btn-primary">
            {t('markDialog.save')}
          </LoadingButton>
        </>
      }
    >
      <fieldset>
        <legend className="ui-label">{t('markDialog.method')}</legend>
        <div className="flex flex-col gap-1">
          {METHODS.map((option) => (
            <label key={option} className="flex min-h-10 items-center gap-2 text-sm text-ink">
              <input type="radio" name="reminder-method" value={option} checked={method === option}
                onChange={() => setMethod(option)} />
              {t(`methods.${option}`)}
            </label>
          ))}
        </div>
      </fieldset>
      <FormField label={<>{t('markDialog.note')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
