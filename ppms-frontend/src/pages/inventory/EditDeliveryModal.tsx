import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { inventoryApi } from '../../api/inventoryApi'
import type { TankerDelivery, UpdateDeliveryRequest } from '../../api/inventoryApi'
import { Modal } from '../../components/Modal'
import { limitDecimals } from './inventoryFormat'

// ── Edit Delivery Modal ────────────────────────────────────────────────────────

export function EditDeliveryModal({ delivery, pumpId, isOwnerOrAdmin, onClose }: {
  delivery: TankerDelivery
  pumpId: number
  isOwnerOrAdmin: boolean
  onClose: () => void
}) {
  const qc = useQueryClient()
  const [invoiceReference, setInvoiceReference] = useState(delivery.invoiceReference)
  const [qty,   setQty]   = useState(String(delivery.quantityDelivered))
  const [cost,  setCost]  = useState(String(delivery.costPricePerUnit))
  const [date,  setDate]  = useState(delivery.deliveryDate.slice(0, 10))
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: UpdateDeliveryRequest) => inventoryApi.updateDelivery(pumpId, delivery.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['deliveries', pumpId] })
      qc.invalidateQueries({ queryKey: ['tankStocks', pumpId] })
      onClose()
    },
    onError: (err: unknown) => {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      setError(msg ?? 'Failed to update delivery.')
    },
  })

  function handleSave() {
    const q = parseFloat(qty)
    if (!invoiceReference.trim()) { setError('Invoice reference is required.'); return }
    if (isNaN(q) || q <= 0) { setError('Quantity must be greater than 0.'); return }
    if (isOwnerOrAdmin) {
      const c = parseFloat(cost)
      if (isNaN(c) || c <= 0) { setError('Cost price must be greater than 0.'); return }
    }
    if (!date) { setError('Delivery date is required.'); return }
    setError(null)
    mutation.mutate({
      invoiceReference: invoiceReference.trim(),
      quantityDelivered: q,
      costPricePerUnit: isOwnerOrAdmin ? parseFloat(cost) : null,
      deliveryDate: date,
    })
  }

  return (
    <Modal
      title="Edit Delivery"
      subtitle={`${delivery.tankIdentifier} · ${delivery.fuelType}`}
      tone="info"
      onClose={onClose}
      closeOnBackdropClick
      bodyClassName="space-y-4"
      footerClassName="justify-end gap-2"
      footer={<>
        <button onClick={onClose} className="ui-btn ui-btn-secondary">Cancel</button>
        <button
          onClick={handleSave}
          disabled={mutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {mutation.isPending ? 'Saving…' : 'Save Changes'}
        </button>
      </>}
    >

      <div className="space-y-1">
        <label className="block">
          <span className="ui-label">Invoice / Bill No.</span>
          <input
            className="ui-input"
            value={invoiceReference}
            onChange={e => setInvoiceReference(e.target.value)}
            placeholder="e.g. INV-2026-001"
          />
        </label>
      </div>

      <div className={`grid gap-3 ${isOwnerOrAdmin ? 'grid-cols-2' : 'grid-cols-1'}`}>
        <div className="space-y-1">
          <label className="block">
            <span className="ui-label">Quantity (L)</span>
            <input
              className="ui-input"
              type="number"
              min="0.001"
              step="0.001"
              value={qty}
              onChange={e => setQty(limitDecimals(e.target.value, 3))}
            />
          </label>
        </div>
        {isOwnerOrAdmin && (
          <div className="space-y-1">
            <label className="block">
              <span className="ui-label">Cost (₹/L)</span>
              <input
                className="ui-input"
                type="number"
                min="0.01"
                step="0.01"
                value={cost}
                onChange={e => setCost(limitDecimals(e.target.value, 2))}
              />
            </label>
          </div>
        )}
      </div>
      {!isOwnerOrAdmin && (
        <p className="text-xs text-ink-3">
          Cost price is managed by Owner / Admin and will not be changed.
        </p>
      )}

      <div className="space-y-1">
        <label className="block">
          <span className="ui-label">Delivery Date</span>
          <input
            className="ui-input"
            type="date"
            value={date}
            onChange={e => setDate(e.target.value)}
          />
        </label>
      </div>

      {error && <p role="alert" className="ui-error-text">{error}</p>}
    </Modal>
  )
}
