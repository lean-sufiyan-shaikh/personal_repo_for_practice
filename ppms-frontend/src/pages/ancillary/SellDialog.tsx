import { useState } from 'react'
import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation(['ancillary', 'common'])
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
      addToast(t('sell.recorded'), 'success')
      onSuccess()
    },
    onError: (err) => {
      const msg = parseApiError(err, t('sell.recordFailed'))
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const handleProceedToReview = () => {
    if (!form.quantityUnits || form.quantityUnits < 1) { setError(t('validation.quantityMin')); return }
    if (!form.sellingPricePerUnit || form.sellingPricePerUnit <= 0) { setError(t('sell.priceInvalid')); return }
    if (form.paymentMode === 'CREDIT' && !form.clientId) { setError(t('validation.creditClient')); return }
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
    : projectedProfit > 0 ? { label: t('sell.projectedProfit', { amount: fmtCurrency(projectedProfit) }), cls: 'bg-ok-bg text-ok border-ok' }
    : projectedProfit < 0 ? { label: t('sell.projectedLoss', { amount: fmtCurrency(projectedProfit) }), cls: 'bg-bad-bg text-bad border-bad' }
    :                        { label: t('sell.breakEven'), cls: 'bg-neutral-bg text-neutral border-border' }
  const headerTone: ModalTone = projectedProfit == null || projectedProfit === 0
    ? 'neutral'
    : projectedProfit > 0 ? 'success' : 'danger'

  const selectedClient = creditClients.find(c => c.id === form.clientId)

  return (
    <Modal
      title={step === 'form' ? t('sell.title') : t('sell.reviewTitle')}
      subtitle={<span className="break-words">{product.displayName}{marginBadge ? ` · ${marginBadge.label}` : ''}</span>}
      tone={headerTone}
      onClose={onClose}
      bodyClassName="space-y-4"
      footerClassName={step === 'review' ? 'justify-between' : ''}
      footer={step === 'form' ? (
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
          <button
            onClick={handleProceedToReview}
            className="ui-btn ui-btn-primary"
          >
            <span className="inline-flex items-center gap-1.5">{t('actions.review')}<ArrowRight size={14} strokeWidth={2} aria-hidden="true" /></span>
          </button>
        </>
      ) : (
        <>
          <button
            onClick={() => { setStep('form'); setError(null) }}
            className="ui-btn ui-btn-secondary"
          >
            <span className="inline-flex items-center gap-1.5"><ChevronLeft size={14} strokeWidth={2} aria-hidden="true" />{t('actions.back')}</span>
          </button>
          <button
            onClick={() => saleMutation.mutate(form)}
            disabled={saleMutation.isPending}
            className="ui-btn ui-btn-primary disabled:opacity-50"
          >
            {saleMutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('actions.recording')}</span>
              : t('sell.confirm')}
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
                <span className="text-ink-3">{t('sell.stock')}</span>
                <span className={`font-medium ${
                  product.currentStockUnits <= (product.lowStockThreshold ?? Infinity) ? 'text-bad' : 'text-ink'
                }`}>{t('sell.stockUnits', { value: product.currentStockUnits })}</span>
              </div>
              {buyPrice != null && (
                <div className="flex items-center gap-1.5 text-xs text-ink-3">
                  <span>{t('sell.buyPrice')}</span>
                  <span className="font-semibold text-ink">{t('fields.perUnit', { price: fmtCurrency(buyPrice) })}</span>
                </div>
              )}
            </div>

            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block">
                    <span className="ui-label">{t('fields.quantityRequired')}</span>
                    <NumericInput
                      min="1" step="1" data-autofocus inputMode="numeric"
                      value={form.quantityUnits}
                      onChange={e => setForm(f => ({ ...f, quantityUnits: parseInt(e.target.value) || 0 }))}
                    />
                  </label>
                </div>
                <div>
                  <label className="block">
                    <span className="ui-label">{t('sell.sellingPriceRequired')}</span>
                    <input
                      type="number" min="0.01" step="0.01"
                      className="text-sm"
                      value={form.sellingPricePerUnit || ''}
                      onChange={e => setForm(f => ({ ...f, sellingPricePerUnit: parseFloat(e.target.value) || 0 }))}
                      placeholder={priceLoading ? t('sell.loadingPrice') : t('sell.mrp')}
                      readOnly={!canOverridePrice}
                      disabled={configuredPrice == null}
                    />
                  </label>
                  {!priceLoading && configuredPrice == null && (
                    <p className="text-xs text-bad mt-1">{t('sell.noPrice')}</p>
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
                <span className="ui-label">{t('fields.paymentModeRequired')}</span>
                <SearchableSelect label={t('fields.paymentMode')}
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
                  <span className="ui-label">{t('fields.clientRequired')}</span>
                  <SearchableSelect label={t('fields.client')}
                    value={form.clientId ? form.clientId.toString() : ''}
                    onChange={v => {
                      const client = creditClients.find(c => c.id === parseInt(v))
                      setForm(f => ({ ...f, clientId: client?.id, clientName: client?.name }))
                    }}
                    placeholder={t('fields.searchClient')}
                    options={creditClients.map(c => ({
                      value: c.id.toString(),
                      label: c.name + (c.phone ? ` · ${c.phone}` : ''),
                    }))}
                  />
                </div>
              )}

              <div>
                <label className="block">
                  <span className="ui-label">{t('fields.billNo')}</span>
                  <input
                    className="text-sm"
                    value={form.billNo ?? ''}
                    onChange={e => setForm(f => ({ ...f, billNo: e.target.value || undefined }))}
                    placeholder={t('fields.optional')}
                  />
                </label>
              </div>
            </div>

            {/* Live total */}
            {totalAmount != null && (
              <div className="bg-info-bg border border-border rounded-control px-4 py-3 flex items-center justify-between">
                <span className="text-sm text-info">{t('fields.total')}</span>
                <MoneyText value={totalAmount} className="text-sm font-semibold text-info" />
              </div>
            )}
          </>
        )}

        {/* Step 2 — Review */}
        {step === 'review' && (
          <>
            <p className="text-sm text-ink-3">{t('sell.reviewHint')}</p>

            <div className="bg-surface-sunken rounded-card border border-border divide-y divide-border text-sm">
              <ReviewRow label={t('fields.product')} value={product.displayName} />
              <ReviewRow label={t('fields.quantity')} value={t('fields.quantityUnits', { count: form.quantityUnits })} />
              {buyPrice != null && <ReviewRow label={t('sell.buyPricePerUnit')} value={fmtCurrency(buyPrice)} />}
              <ReviewRow label={t('sell.sellPricePerUnit')} value={fmtCurrency(form.sellingPricePerUnit)} />
              <ReviewRow label={t('fields.total')} value={fmtCurrency(totalAmount)} highlight />
              {marginBadge && (
                <div className={`flex items-center justify-between px-4 py-2.5 rounded-b-card ${marginBadge.cls}`}>
                  <span className="text-xs">{t('sell.margin')}</span>
                  <span className="text-xs font-semibold">{marginBadge.label}</span>
                </div>
              )}
            </div>

            <div className="bg-surface-sunken rounded-card border border-border divide-y divide-border text-sm">
              <ReviewRow label={t('sell.paymentModeRow')} value={codeLabel(form.paymentMode)} />
              {form.paymentMode === 'CREDIT' && selectedClient && (
                <ReviewRow label={t('fields.client')} value={selectedClient.name} />
              )}
              {form.billNo && <ReviewRow label={t('fields.billNo')} value={form.billNo} />}
            </div>

            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}
          </>
        )}
    </Modal>
  )
}
