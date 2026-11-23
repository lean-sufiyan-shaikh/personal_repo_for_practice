import { useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { ancillaryApi } from '../../api/ancillaryApi'
import { pumpApi } from '../../api/pumpApi'
import type { AncillaryProduct, BackfillSaleRequest, AncillaryPaymentMode } from '../../api/ancillaryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { PAYMENT_MODES, fmtDate } from './ancillaryFormat'
import { ReviewRow } from './ReviewRow'
import { codeLabel } from '../../constants/labels'

// ── Backfill Sale Modal ───────────────────────────────────────────────────────
/**
 * 2-step modal for Admin/Owner to retroactively record a historical counter sale.
 *
 * Key differences from the live SellDialog:
 * - Requires a past date (saleDate strictly before today).
 * - The selling price is NOT entered by the user — the backend resolves it from
 *   the product's price history for the chosen date.
 * - FIFO deduction is restricted to lots delivered on or before saleDate.
 * - Before recording a sale for a date, the product must have a historical stock
 *   delivery on or before that date (enforce this via the Stock In tab first).
 */
export function BackfillSaleModal({
  pumpId, products, onClose, onSuccess,
}: {
  pumpId: number
  products: AncillaryProduct[]
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useTranslation(['ancillary', 'common'])
  const { addToast } = useToastStore()
  const yesterday = localDateInputValue(-1)

  const [step, setStep] = useState<'form' | 'review'>('form')
  const [form, setForm] = useState<BackfillSaleRequest>({
    productId: products[0]?.id ?? 0,
    saleDate: yesterday,
    quantityUnits: 1,
    paymentMode: 'CASH',
  })
  const [error, setError] = useState<string | null>(null)

  const { data: creditClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn: () => pumpApi.getCreditClients(pumpId),
    enabled: form.paymentMode === 'CREDIT',
  })

  const backfillMutation = useMutation({
    mutationFn: (data: BackfillSaleRequest) => ancillaryApi.backfillSale(pumpId, data),
    onSuccess: () => {
      addToast(t('backfill.backfilled'), 'success')
      onSuccess()
    },
    onError: (err) => {
      const msg = parseApiError(err, t('backfill.failed'))
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const selectedProduct = products.find(p => p.id === form.productId) ?? null

  const validate = (): string | null => {
    if (!form.productId) return t('validation.selectProduct')
    if (!form.saleDate) return t('backfill.dateMissing')
    if (form.saleDate >= localDateInputValue()) {
      return t('backfill.dateNotPast')
    }
    if (!form.quantityUnits || form.quantityUnits < 1) return t('validation.quantityMin')
    if (form.paymentMode === 'CREDIT' && !form.clientId) {
      return t('validation.creditClient')
    }
    return null
  }

  const handleReview = () => {
    const err = validate()
    if (err) { setError(err); return }
    setError(null)
    setStep('review')
  }

  return (
    <Modal
      title={step === 'form' ? t('backfill.title') : t('backfill.reviewTitle')}
      subtitle={step === 'form'
                ? t('backfill.subtitle')
                : t('backfill.reviewSubtitle')}
      tone="info"
      onClose={onClose}
      closeOnBackdropClick
      bodyClassName="space-y-4"
      footer={<>
          {step === 'form' ? (
            <>
              <button onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
              <button onClick={handleReview} className="ui-btn ui-btn-primary">{t('actions.reviewNext')}</button>
            </>
          ) : (
            <>
              <button
                onClick={() => { setStep('form'); setError(null) }}
                className="ui-btn ui-btn-secondary"
              >
                <span className="inline-flex items-center gap-1.5"><ChevronLeft size={14} strokeWidth={2} aria-hidden="true" />{t('common:actions.back')}</span>
              </button>
              <button
                onClick={() => backfillMutation.mutate(form)}
                disabled={backfillMutation.isPending}
                className="ui-btn ui-btn-primary disabled:opacity-50"
              >
                {backfillMutation.isPending
                  ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('actions.recording')}</span>
                  : t('backfill.confirm')}
              </button>
            </>
          )}
      </>}
    >

        {/* Step 1 — Form */}
        {step === 'form' && (
          <>
            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}

            {/* Info banner about price resolution */}
            <div className="bg-warn-bg border border-warn rounded-control px-4 py-3 text-xs text-warn">
              <Trans t={t} i18nKey="backfill.note" components={{ strong: <strong /> }} />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="sm:col-span-2">
                <span className="ui-label">{t('fields.productRequired')}</span>
                <SearchableSelect label={t('fields.product')}
                  value={form.productId ? form.productId.toString() : ''}
                  onChange={v => setForm(f => ({ ...f, productId: v ? parseInt(v) : 0 }))}
                  placeholder={t('fields.selectProduct')}
                  options={products.map(p => ({ value: p.id.toString(), label: t('fields.productOption', { name: p.displayName, stock: p.currentStockUnits }) }))}
                />
              </div>

              <div>
                <label className="block">
                  <span className="ui-label">{t('backfill.saleDatePastOnly')}</span>
                  <input
                    type="date"
                    className="text-sm"
                    value={form.saleDate}
                    max={yesterday}
                    onChange={e => setForm(f => ({ ...f, saleDate: e.target.value }))}
                  />
                </label>
              </div>

              <div>
                <label className="block">
                  <span className="ui-label">{t('fields.quantityRequired')}</span>
                  <input
                    type="number"
                    min="1"
                    step="1"
                    className="text-sm"
                    value={form.quantityUnits}
                    onChange={e => setForm(f => ({ ...f, quantityUnits: parseInt(e.target.value) || 0 }))}
                  />
                </label>
              </div>

              <div className="sm:col-span-2">
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
                <div className="sm:col-span-2">
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
                    placeholder={t('common:fields.optional')}
                  />
                </label>
              </div>

              <div>
                <label className="block">
                  <span className="ui-label">{t('common:fields.notes')}</span>
                  <input
                    className="text-sm"
                    value={form.notes ?? ''}
                    onChange={e => setForm(f => ({ ...f, notes: e.target.value || undefined }))}
                    placeholder={t('common:fields.optional')}
                  />
                </label>
              </div>
            </div>
          </>
        )}

        {/* Step 2 — Review */}
        {step === 'review' && (
          <>
            <div className="bg-info-bg border border-info rounded-control px-4 py-2.5 text-xs text-info font-medium">
              <Trans t={t} i18nKey="backfill.reviewBanner" values={{ date: fmtDate(form.saleDate) }} components={{ strong: <strong /> }} />
            </div>

            <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
              <ReviewRow label={t('fields.product')} value={selectedProduct?.displayName ?? '—'} />
              <ReviewRow label={t('backfill.saleDate')} value={fmtDate(form.saleDate)} />
              <ReviewRow label={t('fields.quantity')} value={t('fields.quantityUnits', { count: form.quantityUnits })} />
              <ReviewRow label={t('fields.paymentMode')} value={codeLabel(form.paymentMode)} />
              {form.paymentMode === 'CREDIT' && <ReviewRow label={t('fields.client')} value={form.clientName ?? '—'} />}
              {form.billNo && <ReviewRow label={t('fields.billNo')} value={form.billNo} />}
              {form.notes && <ReviewRow label={t('common:fields.notes')} value={form.notes} />}
              <ReviewRow label={t('backfill.price')} value={t('backfill.priceFromHistory')} />
            </div>

            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}
          </>
        )}

    </Modal>
  )
}
