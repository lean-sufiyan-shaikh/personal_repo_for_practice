import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient, UpdateInterestSettingsRequest } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'

// ─── Set Interest Settings Modal ─────────────────────────────────────────────

interface SetInterestSettingsModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

export function SetInterestSettingsModal({ client, pumpId, onClose }: SetInterestSettingsModalProps) {
  const qc = useQueryClient()
  const [rate, setRate] = useState(client.monthlyInterestRate > 0 ? String(client.monthlyInterestRate) : '')
  const [graceDays, setGraceDays] = useState(String(client.interestGraceDays ?? 1))
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: UpdateInterestSettingsRequest) =>
      creditApi.updateInterestSettings(pumpId, client.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, 'Failed to update interest settings')),
  })

  const handleSubmit = () => {
    const r = rate.trim() === '' ? 0 : parseFloat(rate)
    const g = parseInt(graceDays, 10)
    if (isNaN(r) || r < 0 || r > 100) { setError('Rate must be between 0 and 100'); return }
    if (isNaN(g) || g < 0) { setError('Grace days must be 0 or more'); return }
    setError(null)
    mutation.mutate({ monthlyInterestRate: r, interestGraceDays: g })
  }

  return (
    <Modal
      title="Interest Settings"
      subtitle={client.name}
      tone="warning"
      size="sm"
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
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />Saving…</span> : 'Save Settings'}
          </button>
        </>
      }
    >
      <div className="ui-inline-form">
        <p className="ui-inline-form__copy">
          Simple interest is calculated as: <strong>outstanding × rate × (days / 30)</strong>.
          Set rate to <strong>0</strong> to disable interest for this client.
        </p>

        <span className="ui-label">Monthly Interest Rate (%)</span>
        <div className="relative">
          <input aria-label="Monthly Interest Rate (%)"
            type="number" min="0" max="100" step="0.01"
            value={rate}
            onChange={e => setRate(e.target.value)}
            placeholder="0 = no interest"
            className="w-full pr-8 pl-3 text-sm"
            data-autofocus
          />
          <span className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-3 text-sm">%</span>
        </div>
        <p className="text-xs text-ink-3 mt-1">
          e.g. 2% means ₹100 outstanding → ₹2 interest per month
        </p>
      </div>

      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">Grace Period (days)</span>
          <input
            type="number" min="0" step="1"
            value={graceDays}
            onChange={e => setGraceDays(e.target.value)}
            className="text-sm"
          />
        </label>
        <p className="text-xs text-ink-3 mt-1">
          Interest starts this many days after the first credit sale. Default is 1.
        </p>
      </div>

      {error && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
      )}
    </Modal>
  )
}
