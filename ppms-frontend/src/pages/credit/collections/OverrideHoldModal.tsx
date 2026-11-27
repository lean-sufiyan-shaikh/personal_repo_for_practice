import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { addDays, istDateString } from '../../../utils/date'

interface OverrideHoldModalProps {
  pumpId: number
  clientId: number
  clientName: string
  onClose: () => void
}

/**
 * Lets an Owner or Admin lift a credit hold until a date, with a reason. It is saved as a CREDIT_HOLD_OVERRIDE
 * extension, so it is audited, shown with the other extensions and can be revoked the same way.
 */
export function OverrideHoldModal({ pumpId, clientId, clientName, onClose }: OverrideHoldModalProps) {
  const { t } = useTranslation(['collections', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const tomorrow = addDays(istDateString(), 1)
  const [until, setUntil] = useState(addDays(istDateString(), 7))
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<{ until?: string; reason?: string }>({})
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.createExtension(pumpId, clientId, {
      extensionType: 'CREDIT_HOLD_OVERRIDE', expiryDate: until, reason: reason.trim(),
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['collections', pumpId] })
      qc.invalidateQueries({ queryKey: ['active-extensions', pumpId, clientId] })
      qc.invalidateQueries({ queryKey: ['creditStanding', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId, 'aging'] })
      addToast(t('hold.saved'), 'success')
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('hold.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const found = {
      until: until !== '' && until >= tomorrow ? undefined : t('hold.dateInvalid'),
      reason: reason.trim() ? undefined : t('hold.reasonRequired'),
    }
    setErrors(found)
    if (found.until || found.reason) return
    mutation.mutate()
  }

  return (
    <Modal
      title={t('hold.overrideTitle')}
      subtitle={clientName}
      tone="warning"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('hold.saving')} className="ui-btn-primary">
            {t('hold.override')}
          </LoadingButton>
        </>
      }
    >
      <p className="text-sm text-ink-2">{t('hold.overrideExplanation')}</p>
      <FormField label={t('hold.until')} error={errors.until}>
        <input type="date" value={until} min={tomorrow}
          onChange={(e) => { setUntil(e.target.value); setErrors((current) => ({ ...current, until: undefined })) }} />
      </FormField>
      <FormField label={t('hold.reason')} error={errors.reason}>
        <textarea value={reason} rows={3} maxLength={500} className="resize-none" data-autofocus
          onChange={(e) => { setReason(e.target.value); setErrors((current) => ({ ...current, reason: undefined })) }} />
      </FormField>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
