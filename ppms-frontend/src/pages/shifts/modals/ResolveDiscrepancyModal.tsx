import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { shiftApi } from '../../../api/shiftApi'
import type { ResolveDiscrepancyRequest } from '../../../api/shiftApi'
import type { Shift } from '../../../types/shift'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { parseApiError } from '../../../utils/apiError'
import { Modal } from '../../../components/Modal'
import { formatCurrencyOrDash } from '../../../utils/format'
import { RESOLUTION_OPTIONS, investigationLabel } from '../shiftLabels'

export function ResolveDiscrepancyModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const qc = useQueryClient()
  const [action, setAction] = useState<ResolveDiscrepancyRequest['resolutionAction'] | ''>('')
  const [note,   setNote]   = useState('')
  const [error,  setError]  = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: ResolveDiscrepancyRequest) => shiftApi.resolveDiscrepancy(shift.pumpId, shift.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shiftHistory', shift.pumpId] })
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, 'Failed to resolve discrepancy. Please try again.')),
  })

  const noteRequired = action === 'WAIVED' || action === 'PENDING_INVESTIGATION'

  const handleSubmit = () => {
    if (!action) {
      setError('Choose a resolution action.')
      return
    }
    if (action === 'WAIVED' && !note.trim()) {
      setError('A written reason is required when waiving a discrepancy.')
      return
    }
    if (action === 'PENDING_INVESTIGATION' && !note.trim()) {
      setError('Describe what is being investigated.')
      return
    }
    setError(null)
    mutation.mutate({ resolutionAction: action, resolutionNote: note.trim() || undefined })
  }

  const discType  = shift.discrepancyType
  const discAmt   = shift.discrepancyAmount
  const investigation = investigationLabel(shift)
  // Salary deduction and cash recovery both take money back from the operator, so they only apply to a shortage.
  const resolutionOptions = discType === 'SHORT'
    ? RESOLUTION_OPTIONS
    : RESOLUTION_OPTIONS.filter(option => option.value !== 'SALARY_DEDUCTION' && option.value !== 'CASH_RECOVERY')

  return (
    <tr>
      <td colSpan={8} className="p-0">
        <Modal
          title="Resolve Discrepancy"
          subtitle={`Shift #${shift.id} · ${shift.operatorName} · ${discType} ${formatCurrencyOrDash(discAmt)}`}
          headerExtra={shift.discrepancyReason && (
            <p className="ui-modal-subtitle">Reason given at close: {shift.discrepancyReason}</p>
          )}
          tone="danger"
          size="md"
          onClose={onClose}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={
            <>
              <button onClick={onClose} className="ui-btn ui-btn-secondary flex-1">
                Cancel
              </button>
              <button
                onClick={handleSubmit}
                disabled={mutation.isPending || !action}
                className="ui-btn ui-btn-danger flex-1"
              >
                {mutation.isPending ? 'Saving…' : action === 'PENDING_INVESTIGATION' ? 'Start Investigation' : 'Confirm Resolution'}
              </button>
            </>
          }
        >
          {investigation && (
            <p className="ui-alert ui-alert-warning text-xs">
              {investigation}{shift.investigationNote ? ` — ${shift.investigationNote}` : ''}
            </p>
          )}
          <div>
            <span className="ui-label">Resolution Action</span>
            <SearchableSelect label="Resolution Action"
              value={action}
              onChange={(v) => setAction(v as ResolveDiscrepancyRequest['resolutionAction'])}
              options={resolutionOptions}
              placeholder="Choose an action…"
            />
            {action === 'PENDING_INVESTIGATION' && (
              <p className="text-xs text-ink-3 mt-1">
                The shortage stays open: it still shows for payroll and must get a final action later.
              </p>
            )}
          </div>
          <div>
            <label className="block">
              <span className="ui-label">
                Note {noteRequired && <span className="text-bad">* required</span>}
              </span>
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
                placeholder="Enter justification or notes…"
                className="resize-none text-sm"
              />
            </label>
          </div>
          {error && <p role="alert" className="ui-error-text">{error}</p>}
        </Modal>
      </td>
    </tr>
  )
}
