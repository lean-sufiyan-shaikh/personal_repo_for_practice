import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { creditApi } from '../../../api/creditApi'
import type { CreditExtension } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { ModalPortal } from '../../../components/ModalPortal'
import { parseApiError } from '../../../utils/apiError'
import { extensionSummary } from '../extensionSummary'

interface RevokeExtensionModalProps {
  pumpId: number
  extension: CreditExtension
  onClose: () => void
}

export function RevokeExtensionModal({ pumpId, extension, onClose }: RevokeExtensionModalProps) {
  const qc = useQueryClient()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.revokeExtension(pumpId, extension.clientId, extension.id, reason.trim()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-extensions', pumpId, extension.clientId] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, 'Failed to revoke extension')),
  })

  const handleSubmit = () => {
    if (!reason.trim()) { setError('A reason is required to revoke an extension'); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <ModalPortal onClose={mutation.isPending ? undefined : onClose}>
    <div className="ui-modal-backdrop">
      <div className="ui-modal-panel w-full max-w-md">
        <div className="ui-modal-header ui-modal-header--themed ui-modal-header--warning">
          <div className="ui-modal-heading">
            <h2 className="ui-modal-title">Revoke Extension</h2>
            <p className="ui-modal-subtitle">{extensionSummary(extension)} · expires {extension.expiryDate}</p>
          </div>
          <button type="button" aria-label="Close" onClick={onClose} className="ui-btn ui-btn-ghost ui-modal-close">&times;</button>
        </div>

        <div className="ui-modal-body space-y-4">
          <p className="text-sm text-slate-600">
            The extension stops applying to new credit sales at once. It stays in the client's history as revoked,
            and the revocation is recorded in the audit log.
          </p>
          <div className="ui-inline-form">
            <label className="block">
              <span className="ui-label">Reason <span className="text-red-500">*</span></span>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={3}
                maxLength={500}
                placeholder="e.g. Client missed the promised payment"
                className="resize-none text-sm"
                data-autofocus
              />
            </label>
          </div>
          {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
        </div>

        <div className="ui-modal-footer">
          <button onClick={onClose} className="ui-btn ui-btn-secondary">Keep Extension</button>
          <button onClick={handleSubmit} disabled={mutation.isPending} className="ui-btn ui-btn-danger">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />Revoking…</span> : 'Revoke Extension'}
          </button>
        </div>
      </div>
    </div>
    </ModalPortal>
  )
}
