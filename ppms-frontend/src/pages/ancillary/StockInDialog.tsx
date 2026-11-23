import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct, RecordStockDeliveryRequest } from '../../api/ancillaryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { MoneyText } from '../../components/AmountText'
import { parseApiError } from '../../utils/apiError'
import { ReviewRow } from './ReviewRow'
import { fmtCurrency, fmtDate } from './ancillaryFormat'

export function StockInDialog({
  pumpId, products, onClose, onSuccess,
}: {
  pumpId: number
  products: AncillaryProduct[]
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useTranslation(['ancillary', 'common'])
  const { addToast } = useToastStore()
  const today = localDateInputValue()
  const [step, setStep] = useState<'form' | 'review'>('form')
  const [form, setForm] = useState<RecordStockDeliveryRequest & { productId: number | '' }>({
    productId: products[0]?.id ?? '',
    quantityUnits: 1,
    costPricePerUnit: 0,
    deliveryDate: today,
    invoiceReference: '',
  })
  const [error, setError] = useState<string | null>(null)

  const deliveryMutation = useMutation({
    mutationFn: (data: { productId: number; req: RecordStockDeliveryRequest }) =>
      ancillaryApi.recordDelivery(pumpId, data.productId, data.req),
    onSuccess: () => {
      addToast(t('stockIn.recorded'), 'success')
      onSuccess()
    },
    onError: (err) => {
      const msg = parseApiError(err, t('stockIn.recordFailed'))
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const selectedProduct = products.find(p => p.id === form.productId) ?? null
  const totalCost = form.quantityUnits > 0 && form.costPricePerUnit > 0
    ? form.quantityUnits * form.costPricePerUnit
    : null

  const validate = () => {
    if (!form.productId) return t('validation.selectProduct')
    if (!form.quantityUnits || form.quantityUnits < 1) return t('validation.quantityMin')
    if (!form.costPricePerUnit || form.costPricePerUnit <= 0) return t('stockIn.costInvalid')
    if (!form.deliveryDate) return t('stockIn.dateMissing')
    return null
  }

  const handleReview = () => {
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setStep('review')
  }

  const handleSubmit = () => {
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    deliveryMutation.mutate({
      productId: form.productId as number,
      req: {
        quantityUnits: form.quantityUnits,
        costPricePerUnit: form.costPricePerUnit,
        deliveryDate: form.deliveryDate,
        invoiceReference: form.invoiceReference?.trim() || undefined,
      },
    })
  }

  return (
    <Modal
      title={step === 'form' ? t('stockIn.title') : t('stockIn.reviewTitle')}
      subtitle={step === 'form'
                ? t('stockIn.subtitle')
                : t('stockIn.reviewSubtitle')}
      tone="info"
      onClose={onClose}
      closeOnBackdropClick
      bodyClassName="space-y-4"
      footer={<>
          {step === 'form' ? (
            <>
              <button onClick={onClose} className="ui-btn ui-btn-secondary">
                {t('common:actions.cancel')}
              </button>
              <button onClick={handleReview} className="ui-btn ui-btn-primary">
                {t('common:actions.review')}
              </button>
            </>
          ) : (
            <>
              <button
                onClick={() => { setStep('form'); setError(null) }}
                className="ui-btn ui-btn-secondary"
              >
                {t('common:actions.back')}
              </button>
              <button
                onClick={handleSubmit}
                disabled={deliveryMutation.isPending}
                className="ui-btn ui-btn-primary"
              >
                {deliveryMutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('actions.recording')}</span> : t('stockIn.confirm')}
              </button>
            </>
          )}
      </>}
    >

        {step === 'form' && (
          <>
            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('common:notices.goBackRetry', { error })}</div>
            )}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="sm:col-span-2">
                <span className="ui-label">{t('fields.productRequired')}</span>
                <SearchableSelect label={t('fields.product')}
                  value={form.productId ? form.productId.toString() : ''}
                  onChange={v => setForm(f => ({ ...f, productId: v ? parseInt(v) : '' }))}
                  placeholder={t('fields.selectProduct')}
                  options={products.map(p => ({ value: p.id.toString(), label: t('fields.productOption', { name: p.displayName, stock: p.currentStockUnits }) }))}
                />
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
              <div>
                <label className="block">
                  <span className="ui-label">{t('stockIn.costPriceRequired')}</span>
                  <input
                    type="number"
                    min="0.01"
                    step="0.01"
                    className="text-sm"
                    value={form.costPricePerUnit || ''}
                    onChange={e => setForm(f => ({ ...f, costPricePerUnit: parseFloat(e.target.value) || 0 }))}
                    placeholder="₹"
                  />
                </label>
              </div>
              <div>
                <label className="block">
                  <span className="ui-label">{t('stockIn.deliveryDateRequired')}</span>
                  <input
                    type="date"
                    className="text-sm"
                    value={form.deliveryDate}
                    onChange={e => setForm(f => ({ ...f, deliveryDate: e.target.value }))}
                  />
                </label>
              </div>
              <div>
                <label className="block">
                  <span className="ui-label">{t('stockIn.invoiceReference')}</span>
                  <input
                    className="text-sm"
                    value={form.invoiceReference ?? ''}
                    onChange={e => setForm(f => ({ ...f, invoiceReference: e.target.value }))}
                    placeholder={t('common:fields.optional')}
                  />
                </label>
              </div>
            </div>

            {totalCost != null && (
              <div className="bg-info-bg border border-border rounded-control px-4 py-3 flex items-center justify-between">
                <span className="text-sm text-info">{t('stockIn.estimatedTotal')}</span>
                <MoneyText value={totalCost} className="text-sm font-semibold text-info" />
              </div>
            )}
          </>
        )}

        {step === 'review' && (
          <>
            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('common:notices.goBackRetry', { error })}</div>
            )}
            <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
              <ReviewRow label={t('fields.product')} value={selectedProduct?.displayName ?? '—'} />
              <ReviewRow label={t('fields.quantity')} value={t('fields.quantityUnits', { count: form.quantityUnits })} />
              <ReviewRow label={t('stockIn.costPricePerUnit')} value={fmtCurrency(form.costPricePerUnit)} />
              <ReviewRow label={t('stockIn.deliveryDate')} value={fmtDate(form.deliveryDate)} />
              <ReviewRow label={t('stockIn.invoiceReference')} value={form.invoiceReference?.trim() || '—'} />
              <ReviewRow label={t('stockIn.totalCost')} value={fmtCurrency(totalCost)} highlight />
            </div>
          </>
        )}

    </Modal>
  )
}
