import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { creditApi } from '../../../api/creditApi'
import type { CreditInvoice } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'
import { useToastStore } from '../../../store/toastStore'

// ─── Cancel Invoice Modal ────────────────────────────────────────────────────

interface CancelInvoiceModalProps {
  invoice: CreditInvoice
  pumpId: number
  onClose: () => void
}

export function CancelInvoiceModal({ invoice, pumpId, onClose }: CancelInvoiceModalProps) {
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.cancelInvoice(pumpId, invoice.id, reason.trim()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-invoices', pumpId] })
      addToast(`Invoice ${invoice.invoiceNumber} cancelled`, 'success')
      onClose()
    },
    onError: (err) => setError(parseApiError(err, 'Failed to cancel invoice')),
  })

  const handleSubmit = () => {
    if (!reason.trim()) { setError('A reason is required to cancel an invoice'); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title="Cancel Invoice"
      subtitle={invoice.invoiceNumber}
      tone="warning"
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">Keep Invoice</button>
          <button onClick={handleSubmit} disabled={mutation.isPending} className="ui-btn ui-btn-danger">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />Cancelling…</span> : 'Cancel Invoice'}
          </button>
        </>
      }
    >
      <p className="text-sm text-ink-2">
        The invoice stays on record marked as cancelled and its number is not reused.
        Its sales can then be put on a new invoice.
      </p>
      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">Reason <span aria-hidden="true" className="text-bad">*</span></span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
            placeholder="e.g. Issued for the wrong period"
            className="resize-none text-sm"
            data-autofocus
          />
        </label>
      </div>
      {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
    </Modal>
  )
}
