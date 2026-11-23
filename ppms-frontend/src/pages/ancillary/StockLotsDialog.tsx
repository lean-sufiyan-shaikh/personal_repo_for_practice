import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct, AncillaryLotDetail, UpdateLotRequest } from '../../api/ancillaryApi'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { MoneyText } from '../../components/AmountText'
import { parseApiError } from '../../utils/apiError'
import { fmtDate } from './ancillaryFormat'

// ── Stock Lots Dialog ─────────────────────────────────────────────────────────
/**
 * Shows all ACTIVE FIFO lots for a product in consumption order (oldest first).
 * Each row shows: In Date, Bill No, Cost Price/unit, Remaining, Original.
 * Owner/Admin can edit remaining quantity and cost price inline per row.
 * Editing remaining quantity automatically adjusts product.currentStockUnits on the backend.
 */
export function StockLotsDialog({
  pumpId, product, isOwnerOrAdmin, onClose, onUpdated,
}: {
  pumpId: number
  product: AncillaryProduct
  isOwnerOrAdmin: boolean
  onClose: () => void
  onUpdated: () => void
}) {
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [editingLotId, setEditingLotId] = useState<number | null>(null)
  const [editForm, setEditForm] = useState<{ costPricePerUnit: string; remainingQuantity: string }>({
    costPricePerUnit: '',
    remainingQuantity: '',
  })
  const [editError, setEditError] = useState<string | null>(null)

  const { data: lots = [], isLoading } = useQuery<AncillaryLotDetail[]>({
    queryKey: ['ancillaryLots', pumpId, product.id],
    queryFn:  () => ancillaryApi.getLots(pumpId, product.id),
  })

  const updateMutation = useMutation({
    mutationFn: ({ lotId, data }: { lotId: number; data: UpdateLotRequest }) =>
      ancillaryApi.updateLot(pumpId, lotId, data),
    onSuccess: () => {
      setEditingLotId(null)
      setEditError(null)
      qc.invalidateQueries({ queryKey: ['ancillaryLots', pumpId, product.id] })
      addToast('Lot updated successfully', 'success')
      onUpdated()
    },
    onError: (err) => {
      const msg = parseApiError(err, 'Failed to update lot')
      setEditError(msg)
      addToast(msg, 'error')
    },
  })

  const startEdit = (lot: AncillaryLotDetail) => {
    setEditingLotId(lot.id)
    setEditForm({
      costPricePerUnit: lot.costPricePerUnit.toString(),
      remainingQuantity: lot.remainingQuantity.toString(),
    })
    setEditError(null)
  }

  const handleSave = (lot: AncillaryLotDetail) => {
    const newCost = parseFloat(editForm.costPricePerUnit)
    const newQty  = parseInt(editForm.remainingQuantity, 10)
    if (isNaN(newCost) || newCost <= 0) { setEditError('Cost price must be greater than 0'); return }
    if (isNaN(newQty)  || newQty  <  0) { setEditError('Remaining quantity cannot be negative'); return }
    if (newQty > lot.originalQuantity)  { setEditError(`Remaining quantity cannot exceed original (${lot.originalQuantity})`); return }

    const payload: UpdateLotRequest = {}
    if (newCost !== lot.costPricePerUnit)  payload.costPricePerUnit  = newCost
    if (newQty  !== lot.remainingQuantity) payload.remainingQuantity = newQty
    if (Object.keys(payload).length === 0) { setEditingLotId(null); return }

    updateMutation.mutate({ lotId: lot.id, data: payload })
  }

  const columns: DataTableColumn<AncillaryLotDetail>[] = [
    { key: 'inDate', header: 'In Date' },
    { key: 'billNo', header: 'Bill No' },
    { key: 'costPrice', header: 'Cost Price/Unit', numeric: true },
    { key: 'remaining', header: 'Remaining', numeric: true },
    { key: 'original', header: 'Original', numeric: true },
    ...(isOwnerOrAdmin ? [{ key: 'actions', header: 'Actions', align: 'right' as const }] : []),
  ]

  return (
    <Modal
      title="Stock Lots"
      subtitle={<span className="block truncate max-w-[380px]">{product.displayName}</span>}
      tone="info"
      size="lg"
      onClose={onClose}
      footerClassName="justify-between items-center"
      footer={<>
        <p className="text-xs text-ink-3">
          Lots shown in FIFO order — oldest consumed first
        </p>
        <button onClick={onClose} className="ui-btn ui-btn-secondary">
          Close
        </button>
      </>}
    >
          {isLoading ? (
            <p className="ui-empty py-8">Loading lots…</p>
          ) : lots.length === 0 ? (
            <p className="ui-empty py-8">No active stock lots. Record a stock delivery first.</p>
          ) : (
            <>
              {editError && (
                <div role="alert" className="ui-alert ui-alert-danger mb-3 text-sm">{editError}</div>
              )}
              <DataTable
                caption="Active stock lots"
                captionHidden
                columns={columns}
                rows={lots}
                rowKey={(lot) => lot.id}
                renderRow={(lot) => (
                    <tr>
                      {editingLotId === lot.id ? (
                        <>
                          <td className="text-ink">{fmtDate(lot.deliveryDate)}</td>
                          <td className="text-ink-3">{lot.invoiceReference ?? '—'}</td>
                          <td className="text-right">
                            <input aria-label="Cost price per unit"
                              type="number"
                              min="0.01"
                              step="0.01"
                              value={editForm.costPricePerUnit}
                              onChange={e => setEditForm(f => ({ ...f, costPricePerUnit: e.target.value }))}
                              className="w-24 text-right text-sm"
                            />
                          </td>
                          <td className="text-right">
                            <input aria-label="Remaining units"
                              type="number"
                              min="0"
                              max={lot.originalQuantity}
                              step="1"
                              value={editForm.remainingQuantity}
                              onChange={e => setEditForm(f => ({ ...f, remainingQuantity: e.target.value }))}
                              className="w-20 text-right text-sm"
                            />
                          </td>
                          <td className="text-right text-ink-3">{lot.originalQuantity}</td>
                          <td className="text-right">
                            <div className="flex items-center justify-end gap-2">
                              <button
                                onClick={() => handleSave(lot)}
                                disabled={updateMutation.isPending}
                                className="ui-btn ui-btn-primary min-h-0 px-3 py-1 text-xs disabled:opacity-50"
                              >
                                {updateMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />Saving…</span> : 'Save'}
                              </button>
                              <button
                                onClick={() => { setEditingLotId(null); setEditError(null) }}
                                className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs"
                              >
                                Cancel
                              </button>
                            </div>
                          </td>
                        </>
                      ) : (
                        <>
                          <td className="text-ink">{fmtDate(lot.deliveryDate)}</td>
                          <td className="text-ink-3">{lot.invoiceReference ?? '—'}</td>
                          <td className="text-right"><MoneyText value={lot.costPricePerUnit} className="font-medium text-ink" /></td>
                          <td className="text-right">
                            <span className="font-semibold text-ok">{lot.remainingQuantity}</span>
                          </td>
                          <td className="text-right text-ink-3">{lot.originalQuantity}</td>
                          {isOwnerOrAdmin && (
                            <td className="text-right">
                              <button
                                onClick={() => startEdit(lot)}
                                className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-primary hover:text-primary-strong"
                              >
                                Edit
                              </button>
                            </td>
                          )}
                        </>
                      )}
                    </tr>
                )}
              />
            </>
          )}
    </Modal>
  )
}
