import { useState } from 'react'
import { ModalPortal } from './ModalPortal'
import { Spinner } from './Spinner'

interface RejectReasonDialogProps {
  title: string
  subtitle: string
  isPending: boolean
  error: string | null
  onConfirm: (reason: string) => void
  onClose: () => void
}

/** Asks why something is being rejected; the reason is required and is saved with the rejection. */
export function RejectReasonDialog({ title, subtitle, isPending, error, onConfirm, onClose }: RejectReasonDialogProps) {
  const [reason, setReason] = useState('')
  const [missingReason, setMissingReason] = useState(false)

  const confirm = () => {
    if (!reason.trim()) {
      setMissingReason(true)
      return
    }
    setMissingReason(false)
    onConfirm(reason.trim())
  }

  const shownError = missingReason ? 'Enter a reason for rejecting.' : error

  return (
    <ModalPortal onClose={isPending ? undefined : onClose}>
      <div className="ui-modal-backdrop">
        <div className="ui-modal-panel w-full max-w-md">
          <div className="ui-modal-header ui-modal-header--themed ui-modal-header--danger">
            <div className="ui-modal-heading">
              <h2 className="ui-modal-title">{title}</h2>
              <p className="ui-modal-subtitle">{subtitle}</p>
            </div>
            <button type="button" aria-label="Close" onClick={onClose} disabled={isPending}
              className="ui-btn ui-btn-ghost ui-modal-close">&times;</button>
          </div>

          <div className="ui-modal-body space-y-4">
            <label className="block">
              <span className="ui-label">Reason <span aria-hidden="true" className="text-bad">*</span></span>
              <textarea
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                rows={3}
                maxLength={500}
                required
                aria-invalid={missingReason}
                placeholder="e.g. No bill attached"
                className="resize-none text-sm"
                data-autofocus
              />
            </label>
            {shownError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{shownError}</div>}
          </div>

          <div className="ui-modal-footer">
            <button type="button" onClick={onClose} disabled={isPending} className="ui-btn ui-btn-secondary">Cancel</button>
            <button type="button" onClick={confirm} disabled={isPending} className="ui-btn ui-btn-danger">
              {isPending ? <span className="flex items-center gap-1.5"><Spinner />Rejecting…</span> : 'Reject'}
            </button>
          </div>
        </div>
      </div>
    </ModalPortal>
  )
}
