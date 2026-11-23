import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, ChevronLeft } from 'lucide-react'
import { ancillaryApi } from '../../api/ancillaryApi'
import { pumpApi } from '../../api/pumpApi'
import type { AncillaryProduct, RecordAncillarySaleRequest, AncillaryPaymentMode } from '../../api/ancillaryApi'
import { useAuthStore } from '../../store/authStore'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Spinner } from '../../components/Spinner'
import { NumericInput } from '../../components/NumericInput'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import type { ModalTone } from '../../components/Modal'
import { MoneyText } from '../../components/AmountText'
import { parseApiError } from '../../utils/apiError'
import { PAYMENT_MODES, fmtCurrency } from './ancillaryFormat'
import { ReviewRow } from './ReviewRow'
import { codeLabel } from '../../constants/labels'

// ── Sell Dialog ───────────────────────────────────────────────────────────────
/**
 * Computes the projected total profit for a sale by walking through active FIFO lots
 * in consumption order and computing (sellPrice - costPrice) × consumedQty per batch.
 *
 * This correctly handles multi-batch sales where different lots have different cost prices.
 * Example: lots = [{5 units @ ₹100}, {2 units @ ₹200}], sell 6 @ ₹120
 *   → batch1: (120-100)×5 = 100, batch2: (120-200)×1 = -80 → total = 20
 *   (the old approach of (120-100)×6 = 120 was incorrect)
 *
 * Returns null when lot data is insufficient to cover the requested quantity
 * (e.g. lots were consumed by another sale after the page loaded).
 */
function computeFifoProjectedProfit(
  qty: number,
  sellPricePerUnit: number,
  lots: AncillaryProduct['activeFifoLots'],
): number | null {
  if (!lots || lots.length === 0) return null
  let remaining = qty
  let totalCost = 0
  for (const lot of lots) {
    if (remaining <= 0) break
    const consumed = Math.min(remaining, lot.remainingQuantity)
    totalCost += consumed * lot.costPricePerUnit
    remaining -= consumed
  }
  if (remaining > 0) return null  // insufficient lot data for this quantity
  return qty * sellPricePerUnit - totalCost
}

// 2-step modal: Step 1 = fill form, Step 2 = review & confirm

export function SellDialog({
  pumpId, product, onClose, onSuccess,
}: {
  pumpId: number
  product: AncillaryProduct
  onClose: () => void
  onSuccess: () => void
}) {
  const { addToast } = useToastStore()
  const queryClient = useQueryClient()
  const { user } = useAuthStore()
  const canSeeCost = user?.role !== 'OPERATOR'
  const canOverridePrice = user?.role === 'OWNER' || user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN'
  const [step, setStep] = useState<'form' | 'review'>('form')
  const [form, setForm] = useState<RecordAncillarySaleRequest>({
    productId: product.id,
    quantityUnits: 1,
    sellingPricePerUnit: 0,
    paymentMode: 'CASH',
  })
  const [error, setError] = useState<string | null>(null)

  // Sales must use the configured price; only Owner/Admin may change it for a single sale.
  const { data: priceHistory, isLoading: priceLoading } = useQuery({
    queryKey: ['ancillary-price-history', pumpId, product.id],
    queryFn: () => ancillaryApi.getPriceHistory(pumpId, product.id),
  })
  const [openedAt] = useState(() => Date.now())
  const configuredPrice = priceHistory
    ?.filter(p => new Date(p.effectiveFrom).getTime() <= openedAt)
    .sort((a, b) => new Date(b.effectiveFrom).getTime() - new Date(a.effectiveFrom).getTime())[0]
    ?.pricePerUnit ?? null
  const [appliedPrice, setAppliedPrice] = useState<number | null>(null)
  if (configuredPrice != null && configuredPrice !== appliedPrice) {
    setAppliedPrice(configuredPrice)
    setForm(f => ({ ...f, sellingPricePerUnit: configuredPrice }))
  }

  const { data: creditClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn: () => pumpApi.getCreditClients(pumpId),
    enabled: form.paymentMode === 'CREDIT',
  })

  const saleMutation = useMutation({
    mutationFn: (data: RecordAncillarySaleRequest) => ancillaryApi.recordSale(pumpId, data),
    onSuccess: (_, data) => {
      if (data.paymentMode === 'CREDIT') {
        queryClient.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
        queryClient.invalidateQueries({ queryKey: ['credit-transactions', pumpId] })
      }
      addToast('Sale recorded successfully', 'success')
      onSuccess()
    },
    onError: (err) => {
      const msg = parseApiError(err, 'Failed to record sale')
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const handleProceedToReview = () => {
    if (!form.quantityUnits || form.quantityUnits < 1) { setError('Quantity must be at least 1'); return }
    if (!form.sellingPricePerUnit || form.sellingPricePerUnit <= 0) { setError('Enter a valid selling price greater than 0'); return }
    if (form.paymentMode === 'CREDIT' && !form.clientId) { setError('Please select a client for CREDIT payment'); return }
    setError(null)
    setStep('review')
  }

  const buyPrice   = canSeeCost ? product.fifoCostPricePerUnit : null
  const sellPrice  = form.sellingPricePerUnit > 0 ? form.sellingPricePerUnit : null
  const totalAmount = sellPrice != null && form.quantityUnits > 0
    ? sellPrice * form.quantityUnits
    : null

  // Profit/loss indicator: walks FIFO lots in order so multi-batch sales are correct.
  // e.g. sell 6 from lots [5@100, 2@200] @ 120 → (120-100)×5 + (120-200)×1 = 20, not 120.
  const projectedProfit = (canSeeCost && sellPrice != null && form.quantityUnits > 0)
    ? computeFifoProjectedProfit(form.quantityUnits, sellPrice, product.activeFifoLots)
    : null
  const marginBadge = projectedProfit == null ? null
    : projectedProfit > 0 ? { label: `Projected profit: +${fmtCurrency(projectedProfit)}`, cls: 'bg-ok-bg text-ok border-ok' }
    : projectedProfit < 0 ? { label: `Projected loss: ${fmtCurrency(projectedProfit)}`,    cls: 'bg-bad-bg text-bad border-bad' }
    :                        { label: 'Break-even',                                          cls: 'bg-neutral-bg text-neutral border-border' }
  const headerTone: ModalTone = projectedProfit == null || projectedProfit === 0
    ? 'neutral'
    : projectedProfit > 0 ? 'success' : 'danger'

  const selectedClient = creditClients.find(c => c.id === form.clientId)

  return (
    <Modal
      title={step === 'form' ? 'Record Sale' : 'Review & Confirm'}
      subtitle={<span className="break-words">{product.displayName}{marginBadge ? ` · ${marginBadge.label}` : ''}</span>}
      tone={headerTone}
      onClose={onClose}
      bodyClassName="space-y-4"
      footerClassName={step === 'review' ? 'justify-between' : ''}
      footer={step === 'form' ? (
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">
            Cancel
          </button>
          <button
            onClick={handleProceedToReview}
            className="ui-btn ui-btn-primary"
          >
            <span className="inline-flex items-center gap-1.5">Review<ArrowRight size={14} strokeWidth={2} aria-hidden="true" /></span>
          </button>
        </>
      ) : (
        <>
          <button
            onClick={() => { setStep('form'); setError(null) }}
            className="ui-btn ui-btn-secondary"
          >
            <span className="inline-flex items-center gap-1.5"><ChevronLeft size={14} strokeWidth={2} aria-hidden="true" />Back</span>
          </button>
          <button
            onClick={() => saleMutation.mutate(form)}
            disabled={saleMutation.isPending}
            className="ui-btn ui-btn-primary disabled:opacity-50"
          >
            {saleMutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />Recording…</span>
              : 'Confirm Sale'}
          </button>
        </>
      )}
    >
        {/* Step 1 — Form */}
        {step === 'form' && (
          <>
            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}

            {/* Stock + buy price info row */}
            <div className="bg-surface-sunken rounded-control px-4 py-3 flex items-center justify-between text-sm gap-4">
              <div className="flex items-center gap-3">
                <span className="text-ink-3">Stock</span>
                <span className={`font-medium ${
                  product.currentStockUnits <= (product.lowStockThreshold ?? Infinity) ? 'text-bad' : 'text-ink'
                }`}>{product.currentStockUnits} units</span>
              </div>
              {buyPrice != null && (
                <div className="flex items-center gap-1.5 text-xs text-ink-3">
                  <span>Buy price</span>
                  <span className="font-semibold text-ink">{fmtCurrency(buyPrice)}/unit</span>
                </div>
              )}
            </div>

            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block">
                    <span className="ui-label">Quantity (units) *</span>
                    <NumericInput
                      min="1" step="1" data-autofocus inputMode="numeric"
                      value={form.quantityUnits}
                      onChange={e => setForm(f => ({ ...f, quantityUnits: parseInt(e.target.value) || 0 }))}
                    />
                  </label>
                </div>
                <div>
                  <label className="block">
                    <span className="ui-label">Selling Price (₹/unit) *</span>
                    <input
                      type="number" min="0.01" step="0.01"
                      className="text-sm"
                      value={form.sellingPricePerUnit || ''}
                      onChange={e => setForm(f => ({ ...f, sellingPricePerUnit: parseFloat(e.target.value) || 0 }))}
                      placeholder={priceLoading ? 'Loading…' : 'MRP'}
                      readOnly={!canOverridePrice}
                      disabled={configuredPrice == null}
                    />
                  </label>
                  {!priceLoading && configuredPrice == null && (
                    <p className="text-xs text-bad mt-1">No selling price is set for this product. Set a price first.</p>
                  )}
                </div>
              </div>

              {/* Profit/loss badge — total projected profit across all FIFO batches */}
              {marginBadge && (
                <div className={`rounded-control px-4 py-2 border text-xs font-medium ${marginBadge.cls}`}>
                  {marginBadge.label}
                </div>
              )}

              <div>
                <span className="ui-label">Payment Mode *</span>
                <SearchableSelect label="Payment Mode"
                  value={form.paymentMode}
                  onChange={v => setForm(f => ({
                    ...f,
                    paymentMode: v as AncillaryPaymentMode,
                    clientId: v !== 'CREDIT' ? undefined : f.clientId,
                    clientName: v !== 'CREDIT' ? undefined : f.clientName,
                  }))}
                  options={PAYMENT_MODES.map(m => ({ value: m, label: codeLabel(m) }))}
                />
              </div>

              {form.paymentMode === 'CREDIT' && (
                <div>
                  <span className="ui-label">Client * (required for credit)</span>
                  <SearchableSelect label="Client"
                    value={form.clientId ? form.clientId.toString() : ''}
                    onChange={v => {
                      const client = creditClients.find(c => c.id === parseInt(v))
                      setForm(f => ({ ...f, clientId: client?.id, clientName: client?.name }))
                    }}
                    placeholder="Search client…"
                    options={creditClients.map(c => ({
                      value: c.id.toString(),
                      label: c.name + (c.phone ? ` · ${c.phone}` : ''),
                    }))}
                  />
                </div>
              )}

              <div>
                <label className="block">
                  <span className="ui-label">Bill No</span>
                  <input
                    className="text-sm"
                    value={form.billNo ?? ''}
                    onChange={e => setForm(f => ({ ...f, billNo: e.target.value || undefined }))}
                    placeholder="Optional"
                  />
                </label>
              </div>
            </div>

            {/* Live total */}
            {totalAmount != null && (
              <div className="bg-info-bg border border-border rounded-control px-4 py-3 flex items-center justify-between">
                <span className="text-sm text-info">Total</span>
                <MoneyText value={totalAmount} className="text-sm font-semibold text-info" />
              </div>
            )}
          </>
        )}

        {/* Step 2 — Review */}
        {step === 'review' && (
          <>
            <p className="text-sm text-ink-3">Please review the details before confirming.</p>

            <div className="bg-surface-sunken rounded-card border border-border divide-y divide-border text-sm">
              <ReviewRow label="Product" value={product.displayName} />
              <ReviewRow label="Quantity" value={`${form.quantityUnits} unit${form.quantityUnits !== 1 ? 's' : ''}`} />
              {buyPrice != null && <ReviewRow label="Buy price / unit" value={fmtCurrency(buyPrice)} />}
              <ReviewRow label="Sell price / unit" value={fmtCurrency(form.sellingPricePerUnit)} />
              <ReviewRow label="Total" value={fmtCurrency(totalAmount)} highlight />
              {marginBadge && (
                <div className={`flex items-center justify-between px-4 py-2.5 rounded-b-card ${marginBadge.cls}`}>
                  <span className="text-xs">Margin</span>
                  <span className="text-xs font-semibold">{marginBadge.label}</span>
                </div>
              )}
            </div>

            <div className="bg-surface-sunken rounded-card border border-border divide-y divide-border text-sm">
              <ReviewRow label="Payment mode" value={codeLabel(form.paymentMode)} />
              {form.paymentMode === 'CREDIT' && selectedClient && (
                <ReviewRow label="Client" value={selectedClient.name} />
              )}
              {form.billNo && <ReviewRow label="Bill No" value={form.billNo} />}
            </div>

            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}
          </>
        )}
    </Modal>
  )
}
