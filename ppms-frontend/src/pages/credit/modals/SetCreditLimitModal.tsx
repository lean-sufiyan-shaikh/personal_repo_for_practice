import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'

// ─── Set Credit Limit Modal ──────────────────────────────────────────────────

interface SetCreditLimitModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

export function SetCreditLimitModal({ client, pumpId, onClose }: SetCreditLimitModalProps) {
  const qc = useQueryClient()
  const [limit, setLimit] = useState(client.creditLimit > 0 ? String(client.creditLimit) : '')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (creditLimit: number) =>
      creditApi.updateCreditLimit(pumpId, client.id, { creditLimit }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      onClose()
    },
    onError: (err) => {
      setError(parseApiError(err, 'Failed to update credit limit'))
    },
  })

  const handleSubmit = () => {
    const val = limit.trim() === '' ? 0 : parseFloat(limit)
    if (isNaN(val) || val < 0) {
      setError('Credit limit must be 0 or a positive number')
      return
    }
    setError(null)
    mutation.mutate(val)
  }

  return (
    <Modal
      title="Set Credit Limit"
      subtitle={client.name}
      tone="info"
      size="lg"
      onClose={onClose}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />Saving…</span> : 'Save Limit'}
          </button>
        </>
      }
    >
      <div className="ui-inline-form">
        <p className="ui-inline-form__copy">
          Enter the maximum credit balance allowed. Set to <strong>0</strong> to allow unlimited credit.
        </p>
        <span className="ui-label">Credit Limit (₹)</span>
        <div className="relative">
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-3 text-sm">₹</span>
          <input aria-label="Credit Limit (₹)"
            type="number"
            min="0"
            step="0.01"
            value={limit}
            onChange={e => setLimit(e.target.value)}
            placeholder="0 = unlimited"
            className="w-full pl-7 pr-3 text-sm"
            data-autofocus
          />
        </div>
      </div>

      {error && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">
          {error}
        </div>
      )}
    </Modal>
  )
}
