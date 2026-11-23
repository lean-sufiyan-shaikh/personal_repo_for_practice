import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { Settlement } from '../../api/paymentSettlementApi'
import { Modal } from '../../components/Modal'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { TYPE_LABELS, fmtAmt, fmtDate } from './settlementFormat'

interface VoidSettlementDialogProps {
  pumpId: number
  voidTarget: Settlement
  onClose: () => void
}

export function VoidSettlementDialog({ pumpId, voidTarget, onClose }: VoidSettlementDialogProps) {
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [voidReason, setVoidReason] = useState('')
  const [voidError, setVoidError]   = useState<string | null>(null)

  const voidMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      paymentSettlementApi.voidSettlement(pumpId, id, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['settlements-daily', pumpId] })
      qc.invalidateQueries({ queryKey: ['settlements-wallet', pumpId] })
      onClose()
      addToast('Settlement voided', 'success')
    },
    onError: (err) => setVoidError(parseApiError(err, 'Failed to void settlement')),
  })

  const handleVoid = (e: React.FormEvent) => {
    e.preventDefault()
    if (!voidReason.trim()) { setVoidError('A reason is required'); return }
    voidMutation.mutate({ id: voidTarget.id, reason: voidReason.trim() })
  }

  return (
    <Modal
      title="Void Settlement"
      subtitle={<>{TYPE_LABELS[voidTarget.paymentType]} · {fmtDate(voidTarget.settlementDate)} · {fmtAmt(voidTarget.amountReceived)}</>}
      size="lg"
      onClose={onClose}
      closeOnBackdropClick
      onSubmit={handleVoid}
      bodyClassName="flex flex-col gap-5"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          Cancel
        </button>
        <button type="submit" disabled={voidMutation.isPending} className="ui-btn ui-btn-danger">
          {voidMutation.isPending ? <><Spinner /><span>Voiding…</span></> : <span>Void Settlement</span>}
        </button>
      </>}
    >
      <p className="text-sm text-ink-2">
        The settlement stays on record but no longer counts as received, so the pending balance goes back up.
      </p>
      <div>
        <label className="block">
          <span className="ui-label">Reason</span>
          <input
            type="text"
            className="ui-input"
            placeholder="e.g., Recorded twice by mistake"
            value={voidReason}
            onChange={e => setVoidReason(e.target.value)}
            data-autofocus
          />
        </label>
      </div>

      {voidError && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm py-2">
          {voidError}
        </div>
      )}
    </Modal>
  )
}
