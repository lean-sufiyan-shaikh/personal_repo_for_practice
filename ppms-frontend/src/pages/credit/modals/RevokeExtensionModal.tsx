import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditExtension } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'
import { extensionSummary } from '../extensionSummary'

interface RevokeExtensionModalProps {
  pumpId: number
  extension: CreditExtension
  onClose: () => void
}

export function RevokeExtensionModal({ pumpId, extension, onClose }: RevokeExtensionModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.revokeExtension(pumpId, extension.clientId, extension.id, reason.trim()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-extensions', pumpId, extension.clientId] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('revokeExtension.failed'))),
  })

  const handleSubmit = () => {
    if (!reason.trim()) { setError(t('revokeExtension.reasonRequired')); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title={t('revokeExtension.title')}
      subtitle={t('revokeExtension.subtitle', { summary: extensionSummary(extension), date: extension.expiryDate })}
      tone="warning"
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">{t('revokeExtension.keep')}</button>
          <button onClick={handleSubmit} disabled={mutation.isPending} className="ui-btn ui-btn-danger">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('revokeExtension.revoking')}</span> : t('revokeExtension.title')}
          </button>
        </>
      }
    >
      <p className="text-sm text-ink-2">
        {t('revokeExtension.explanation')}
      </p>
      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">{t('common:fields.reason')} <span aria-hidden="true" className="text-bad">*</span></span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
            placeholder={t('revokeExtension.reasonPlaceholder')}
            className="resize-none text-sm"
            data-autofocus
          />
        </label>
      </div>
      {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
    </Modal>
  )
}
