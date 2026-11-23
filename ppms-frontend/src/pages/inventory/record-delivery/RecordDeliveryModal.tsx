import { useState } from 'react'
import { codeLabel } from '../../../constants/labels'
import { useTranslation } from 'react-i18next'
import { useOnValuesChange } from '../../../hooks/useOnValuesChange'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { inventoryApi } from '../../../api/inventoryApi'
import { tankerApi } from '../../../api/tankerApi'
import type { Tanker } from '../../../api/tankerApi'
import type { TankStock, RecordBatchDeliveryRequest } from '../../../api/inventoryApi'
import { localDateInputValue } from '../../../utils/date'
import { Modal } from '../../../components/Modal'
import { MoneyText } from '../../../components/AmountText'
import { parseApiError } from '../../../utils/apiError'
import { Spinner } from '../../../components/Spinner'
import { useToastStore } from '../../../store/toastStore'
import { limitDecimals } from '../inventoryFormat'
import { _rowSeq, newRow } from './deliveryRows'
import type { DeliveryRow, PreparedDeliveryItem } from './deliveryRows'
import { DeliveryRowEditor } from './DeliveryRowEditor'
import { DeliveryHeaderFields } from './DeliveryHeaderFields'
import { DeliveryReviewDialog } from './DeliveryReviewDialog'
import { NumericInput } from '../../../components/NumericInput'
import { formatNumberIn } from '../../../i18n/format'

export function RecordDeliveryModal({
  pumpId,
  tanks,
  isOwnerOrAdmin,
  canSetBillTotal,
  onClose,
}: {
  pumpId: number
  tanks: TankStock[]
  isOwnerOrAdmin: boolean
  canSetBillTotal: boolean
  onClose: () => void
}) {
  const { t } = useTranslation(['inventory', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const defaultTankId = tanks[0]?.tankId ? String(tanks[0].tankId) : ''

  const { data: tankers = [] } = useQuery<Tanker[]>({
    queryKey: ['tankers', pumpId],
    queryFn: () => tankerApi.getTankers(pumpId),
  })

  const [date,              setDate]              = useState(localDateInputValue())
  const [invoice,           setInvoice]           = useState('')
  const [rows,              setRows]              = useState<DeliveryRow[]>([newRow(defaultTankId)])
  const [expandedRowId,     setExpandedRowId]     = useState<number | null>(() => _rowSeq)
  const [error,             setError]             = useState<string | null>(null)
  const [reviewOpen,        setReviewOpen]        = useState(false)
  const [billTotalStr,      setBillTotalStr]      = useState('')
  const [billTotalManual,   setBillTotalManual]   = useState(false)
  const [selectedTankerId,  setSelectedTankerId]  = useState<number | null>(
    () => tankers.find(t => t.defaultTanker)?.id ?? null
  )

  const mutation = useMutation({
    mutationFn: (req: RecordBatchDeliveryRequest) =>
      inventoryApi.recordBatchDelivery(pumpId, req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['deliveries', pumpId] })
      addToast(t('recordDelivery.recorded'), 'success')
      onClose()
    },
    onError: (err) =>
      setError(parseApiError(err, t('recordDelivery.failed'))),
  })

  const updateRow = (id: number, field: keyof Omit<DeliveryRow, 'id'>, value: string) =>
    setRows(prev => prev.map(r => r.id === id ? { ...r, [field]: value } : r))

  const addRow = () => {
    const r = newRow(defaultTankId)
    setRows(prev => [...prev, r])
    setExpandedRowId(r.id)
  }

  const removeRow = (id: number) => {
    setRows(prev => prev.filter(r => r.id !== id))
    if (expandedRowId === id) setExpandedRowId(null)
  }

  const grandTotal = isOwnerOrAdmin
    ? rows.reduce((sum, r) => {
        const q = parseFloat(r.qty) || 0
        const c = parseFloat(r.costPrice) || 0
        return sum + q * c
      }, 0)
    : 0

  const defaultTankerId = tankers.find(t => t.defaultTanker)?.id ?? null
  useOnValuesChange([defaultTankerId], () => {
    if (defaultTankerId !== null && selectedTankerId === null) setSelectedTankerId(defaultTankerId)
  })

  const billTotalText = billTotalManual ? billTotalStr : (grandTotal > 0 ? grandTotal.toFixed(2) : '')
  const billTotalNum = parseFloat(billTotalText) || 0
  const otherCharges = billTotalNum - grandTotal

  const selectedTanker = tankers.find(t => t.id === selectedTankerId) ?? null
  const totalEnteredQty = rows.reduce((s, r) => s + (parseFloat(r.qty) || 0), 0)
  const tankerRemaining = selectedTanker ? selectedTanker.capacityLitres - totalEnteredQty : null
  const tankerMismatch = selectedTanker !== null && Math.abs(totalEnteredQty - selectedTanker.capacityLitres) > 0.01

  const anyTankOverflow = rows.some(r => {
    const tank = tanks.find(tk => tk.tankId === Number(r.tankId))
    const q = parseFloat(r.qty) || 0
    return tank && tank.capacity > 0 && q > 0 && q > (tank.capacity - tank.currentStock)
  })

  const buildPreparedItems = (): { items: PreparedDeliveryItem[] | null; error: string | null } => {
    if (!date || !invoice.trim()) {
      return { items: null, error: t('recordDelivery.dateAndInvoiceRequired') }
    }

    for (const r of rows) {
      if (!r.tankId) return { items: null, error: t('recordDelivery.selectTankEveryRow') }
      const q = parseFloat(r.qty)
      if (!r.qty || isNaN(q) || q <= 0) return { items: null, error: t('recordDelivery.quantityEveryRow') }
      const rowTank = tanks.find(tk => tk.tankId === Number(r.tankId))
      if (rowTank && rowTank.capacity > 0) {
        const availableSpace = rowTank.capacity - rowTank.currentStock
        if (q > availableSpace) {
          return {
            items: null,
            error: t('recordDelivery.exceedsSpace', {
              tank: rowTank.tankIdentifier,
              quantity: formatNumberIn(q, { minimumFractionDigits: 1 }),
              space: formatNumberIn(availableSpace, { minimumFractionDigits: 1 }),
            }),
          }
        }
      }
      if (isOwnerOrAdmin) {
        const c = parseFloat(r.costPrice)
        if (!r.costPrice || isNaN(c) || c <= 0) return { items: null, error: t('recordDelivery.costEveryRow') }
      }
    }

    const selectedTankIds = rows.map(r => r.tankId)
    const unique = new Set(selectedTankIds)
    if (unique.size !== selectedTankIds.length) {
      return { items: null, error: t('recordDelivery.duplicateTank') }
    }

    const preparedItems = rows.map((r) => {
      const tank = tanks.find(tk => tk.tankId === Number(r.tankId))
      if (!tank) {
        throw new Error('Selected tank not found.')
      }

      const quantityDelivered = parseFloat(r.qty)
      const costPricePerUnit = isOwnerOrAdmin ? parseFloat(r.costPrice) : 0

      return {
        tankId: tank.tankId,
        tankIdentifier: tank.tankIdentifier,
        fuelType: tank.fuelType,
        quantityDelivered,
        costPricePerUnit,
        totalCost: isOwnerOrAdmin ? quantityDelivered * costPricePerUnit : 0,
        stockAfter: tank.currentStock + quantityDelivered,
        capacity: tank.capacity,
      }
    })

    return { items: preparedItems, error: null }
  }

  const handleReview = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)

    try {
      const { items, error } = buildPreparedItems()
      if (error || !items) {
        setError(error)
        return
      }

      setReviewOpen(true)
    } catch {
      setError(t('recordDelivery.tankNotFound'))
      return
    }
  }

  const handleConfirmSubmit = () => {
    try {
      const { items, error } = buildPreparedItems()
      if (error || !items) {
        setError(error)
        return
      }

      mutation.mutate({
        deliveryDate:     date,
        invoiceReference: invoice.trim(),
        billTotal: canSetBillTotal && billTotalNum > 0 ? billTotalNum : null,
        tankerId: selectedTankerId ?? null,
        items: items.map(item => ({
          tankId: item.tankId,
          quantityDelivered: item.quantityDelivered,
          costPricePerUnit: isOwnerOrAdmin ? item.costPricePerUnit : null,
        })),
      })
    } catch {
      setError(t('recordDelivery.tankNotFound'))
    }
  }

  const tankOptions = tanks.map(tank => ({
    value: String(tank.tankId),
    label: t('recordDelivery.tankOption', { tank: tank.tankIdentifier, fuel: codeLabel(tank.fuelType), stock: tank.currentStock.toFixed(0) }),
  }))

  return (
    <>
    <Modal
      title={t('recordDelivery.title')}
      subtitle={t('recordDelivery.subtitle')}
      tone="info"
      size="lg"
      onClose={onClose}
      closeOnBackdropClick
      onSubmit={handleReview}
      bodyClassName="space-y-3"
      footerClassName="flex-col items-stretch"
      footer={<>
            {/* Bill total — visible to OWNER/ADMIN (auto-filled from calc) and MANAGER (manual entry) */}
            {canSetBillTotal && (
              <div className="space-y-2 rounded-card bg-info-bg px-4 py-3">
                {isOwnerOrAdmin && grandTotal > 0 && (
                  <div className="flex items-center justify-between">
                    <p className="text-xs text-info font-medium">
                      {t('recordDelivery.calculatedTotal', { count: rows.length })}
                    </p>
                    <MoneyText value={grandTotal} className="text-sm font-semibold text-info" />
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <label htmlFor="delivery-bill-total" className="text-xs text-info font-medium shrink-0">{t('recordDelivery.billAmount')}</label>
                  <NumericInput id="delivery-bill-total"
                    step="0.01"
                    min="0.01"
                    value={billTotalText}
                    onChange={e => { setBillTotalStr(limitDecimals(e.target.value, 2)); setBillTotalManual(true) }}
                    onBlur={() => { if (!billTotalText.trim()) setBillTotalManual(false) }}
                    className="ui-input text-sm font-semibold text-ink bg-surface flex-1 min-w-0"
                    placeholder={t('recordDelivery.billAmountPlaceholder')}
                  />
                </div>
                {!isOwnerOrAdmin && (
                  <p className="text-xs text-ink-3">{t('recordDelivery.supplierInvoiceNote')}</p>
                )}
                {isOwnerOrAdmin && billTotalManual && otherCharges !== 0 && (
                  <p className="text-xs text-info">
                    {t('recordDelivery.otherCharges')}{' '}
                    <span className={`font-medium ${otherCharges > 0 ? 'text-warn' : 'text-ok'}`}>
                      {otherCharges > 0 ? '+' : ''}₹{formatNumberIn(Math.abs(otherCharges), { minimumFractionDigits: 2 })}
                    </span>
                    <span className="text-ink-3"> {t('recordDelivery.otherChargesHint')}</span>
                  </p>
                )}
              </div>
            )}

            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-xs">
                {error}
              </div>
            )}

            <div className="flex gap-3">
              <button
                type="button"
                onClick={onClose}
                className="ui-btn ui-btn-secondary flex-1"
              >
                {t('common:actions.cancel')}
              </button>
              <button
                type="submit"
                disabled={mutation.isPending || tankerMismatch || anyTankOverflow}
                title={
                  tankerMismatch ? t('recordDelivery.mustEqualTanker', { capacity: formatNumberIn(selectedTanker?.capacityLitres ?? 0) })
                  : anyTankOverflow ? t('recordDelivery.tankOverflow')
                  : undefined
                }
                className="ui-btn ui-btn-primary flex-1 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('recordDelivery.recording')}</span> : t('recordDelivery.review')}
              </button>
            </div>
      </>}
    >
      {/* Shared fields — delivery date + invoice + tanker */}
      <DeliveryHeaderFields
        date={date}
        setDate={setDate}
        invoice={invoice}
        setInvoice={setInvoice}
        tankers={tankers}
        selectedTanker={selectedTanker}
        selectedTankerId={selectedTankerId}
        setSelectedTankerId={setSelectedTankerId}
        tankerMismatch={tankerMismatch}
        tankerRemaining={tankerRemaining}
        totalEnteredQty={totalEnteredQty}
      />

      {rows.map((row, idx) => (
        <DeliveryRowEditor
          key={row.id}
          row={row}
          rowNumber={idx + 1}
          tanks={tanks}
          tankOptions={tankOptions}
          isOwnerOrAdmin={isOwnerOrAdmin}
          isExpanded={expandedRowId === row.id}
          onExpandedChange={setExpandedRowId}
          canRemove={rows.length > 1}
          onRemove={() => removeRow(row.id)}
          onChange={(field, value) => updateRow(row.id, field, value)}
        />
      ))}

      {/* Add another tank button */}
      <button
        type="button"
        onClick={addRow}
        className="ui-btn ui-btn-secondary w-full border-2 border-dashed"
      >
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
        </svg>
        {t('recordDelivery.addTank')}
      </button>

    </Modal>
      {reviewOpen && (() => {
        try {
          const { items } = buildPreparedItems()
          if (!items) return null

          return (
        <DeliveryReviewDialog
          items={items}
          date={date}
          invoice={invoice}
          error={error}
          isOwnerOrAdmin={isOwnerOrAdmin}
          canSetBillTotal={canSetBillTotal}
          billTotalManual={billTotalManual}
          billTotalNum={billTotalNum}
          otherCharges={otherCharges}
          isPending={mutation.isPending}
          onBack={() => setReviewOpen(false)}
          onConfirm={handleConfirmSubmit}
        />
          )
        } catch {
          return null
        }
      })()}
    </>
  )
}
