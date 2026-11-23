import { useState } from 'react'
import { codeLabel } from '../../constants/labels'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation(['inventory', 'common'])
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
      setError(msg ?? t('editDelivery.failed'))
    },
  })

  function handleSave() {
    const q = parseFloat(qty)
    if (!invoiceReference.trim()) { setError(t('editDelivery.invoiceRequired')); return }
    if (isNaN(q) || q <= 0) { setError(t('editDelivery.quantityPositive')); return }
    if (isOwnerOrAdmin) {
      const c = parseFloat(cost)
      if (isNaN(c) || c <= 0) { setError(t('editDelivery.costPositive')); return }
    }
    if (!date) { setError(t('editDelivery.dateRequired')); return }
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
      title={t('editDelivery.title')}
      subtitle={`${delivery.tankIdentifier} · ${codeLabel(delivery.fuelType)}`}
      tone="info"
      onClose={onClose}
      closeOnBackdropClick
      bodyClassName="space-y-4"
      footerClassName="justify-end gap-2"
      footer={<>
        <button onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
        <button
          onClick={handleSave}
          disabled={mutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {mutation.isPending ? t('common:actions.saving') : t('editDelivery.save')}
        </button>
      </>}
    >

      <div className="space-y-1">
        <label className="block">
          <span className="ui-label">{t('editDelivery.invoice')}</span>
          <input
            className="ui-input"
            value={invoiceReference}
            onChange={e => setInvoiceReference(e.target.value)}
            placeholder={t('editDelivery.invoicePlaceholder')}
          />
        </label>
      </div>

      <div className={`grid gap-3 ${isOwnerOrAdmin ? 'grid-cols-2' : 'grid-cols-1'}`}>
        <div className="space-y-1">
          <label className="block">
            <span className="ui-label">{t('editDelivery.quantity')}</span>
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
              <span className="ui-label">{t('editDelivery.cost')}</span>
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
          {t('editDelivery.costManaged')}
        </p>
      )}

      <div className="space-y-1">
        <label className="block">
          <span className="ui-label">{t('editDelivery.date')}</span>
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
