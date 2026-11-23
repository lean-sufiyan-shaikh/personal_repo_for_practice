import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { creditApi } from '../../../api/creditApi'
import type { CreditTransaction } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { ModalPortal } from '../../../components/ModalPortal'
import { parseApiError } from '../../../utils/apiError'
import { formatDecimal } from '../../../utils/format'

// ─── Waive Interest Modal ────────────────────────────────────────────────────

interface WaiveInterestModalProps {
  pumpId: number
  clientId: number
  charge: CreditTransaction
  onWaived: () => void
  onClose: () => void
}

export function WaiveInterestModal({ pumpId, clientId, charge, onWaived, onClose }: WaiveInterestModalProps) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.waiveInterestCharge(pumpId, clientId, charge.referenceId, reason.trim()),
    onSuccess: () => {
      onWaived()
      onClose()
    },
    onError: (err) => setError(parseApiError(err, 'Failed to waive interest')),
  })

  const handleSubmit = () => {
    if (!reason.trim()) { setError('A reason is required to waive interest'); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <ModalPortal onClose={mutation.isPending ? undefined : onClose}>
    <div className="ui-modal-backdrop">
      <div className="ui-modal-panel w-full max-w-md">
        <div className="ui-modal-header ui-modal-header--themed ui-modal-header--warning">
          <div className="ui-modal-heading">
            <h2 className="ui-modal-title">Waive Interest</h2>
            <p className="ui-modal-subtitle">₹{formatDecimal(charge.amount)} · {charge.detail}</p>
          </div>
          <button type="button" aria-label="Close" onClick={onClose} className="ui-btn ui-btn-ghost ui-modal-close">&times;</button>
        </div>

        <div className="ui-modal-body space-y-4">
          <p className="text-sm text-slate-600">
            The charge stays on the ledger marked as waived and is no longer owed. The waiver is recorded in the audit log.
            If this is the latest charge, the next interest run can charge its period again.
          </p>
          <div className="ui-inline-form">
            <label className="block">
              <span className="ui-label">Reason <span className="text-red-500">*</span></span>
              <textarea
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={3}
                maxLength={500}
                placeholder="e.g. Charged at the wrong rate"
                className="resize-none text-sm"
                data-autofocus
              />
            </label>
          </div>
          {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
        </div>

        <div className="ui-modal-footer">
          <button onClick={onClose} className="ui-btn ui-btn-secondary">Keep Charge</button>
          <button onClick={handleSubmit} disabled={mutation.isPending} className="ui-btn ui-btn-danger">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />Waiving…</span> : 'Waive Interest'}
          </button>
        </div>
      </div>
    </div>
    </ModalPortal>
  )
}
