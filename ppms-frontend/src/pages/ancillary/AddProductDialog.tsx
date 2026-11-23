import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation } from '@tanstack/react-query'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { CreateProductRequest, UnitOfMeasure } from '../../api/ancillaryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { ReviewRow } from './ReviewRow'
import { UNITS } from './ancillaryFormat'
import { isValidHsnCode } from '../../utils/gst'

export function AddProductDialog({
  pumpId, onClose, onSuccess,
}: {
  pumpId: number
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useTranslation(['ancillary', 'common'])
  const { addToast } = useToastStore()
  const [step, setStep] = useState<'form' | 'review'>('form')
  const [form, setForm] = useState<CreateProductRequest>({
    name: '', brand: '', variant: '', hsnCode: '',
    packageSize: 1, unitOfMeasure: 'PIECE',
  })
  const [error, setError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: CreateProductRequest) => ancillaryApi.createProduct(pumpId, data),
    onSuccess: () => {
      addToast(t('addProduct.created'), 'success')
      onSuccess()
    },
    onError: (err) => {
      const msg = parseApiError(err, t('addProduct.createFailed'))
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const normalizedName = form.name.trim()
  const normalizedBrand = form.brand?.trim() || undefined
  const normalizedVariant = form.variant?.trim() || undefined
  const normalizedThreshold = form.lowStockThreshold && form.lowStockThreshold > 0 ? form.lowStockThreshold : undefined
  const normalizedHsnCode = form.hsnCode?.trim() || undefined

  const validate = () => {
    if (!normalizedName) return t('addProduct.nameMissing')
    if (!form.packageSize || form.packageSize <= 0) return t('addProduct.packageSizePositive')
    if (!isValidHsnCode(form.hsnCode ?? '')) return t('validation.hsnInvalid')
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

  const handleCreate = () => {
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    createMutation.mutate({
      name: normalizedName,
      brand: normalizedBrand,
      variant: normalizedVariant,
      packageSize: form.packageSize,
      unitOfMeasure: form.unitOfMeasure,
      lowStockThreshold: normalizedThreshold,
      hsnCode: normalizedHsnCode,
    })
  }

  return (
    <Modal
      title={step === 'form' ? t('addProduct.title') : t('addProduct.reviewTitle')}
      subtitle={step === 'form'
        ? t('addProduct.subtitle')
        : t('addProduct.reviewSubtitle')}
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
                onClick={handleCreate}
                disabled={createMutation.isPending}
                className="ui-btn ui-btn-primary"
              >
                {createMutation.isPending
                  ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('addProduct.creating')}</span>
                  : t('addProduct.create')}
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
              <div>
                <label className="block">
                  <span className="ui-label">{t('addProduct.nameRequired')}</span>
                  <input
                    className="text-sm"
                    value={form.name}
                    onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    placeholder={t('addProduct.namePlaceholder')}
                    data-autofocus
                  />
                </label>
              </div>
              <div>
                <label className="block">
                  <span className="ui-label">{t('addProduct.brand')}</span>
                  <input
                    className="text-sm"
                    value={form.brand ?? ''}
                    onChange={e => setForm(f => ({ ...f, brand: e.target.value }))}
                    placeholder={t('addProduct.brandPlaceholder')}
                  />
                </label>
              </div>
              <div>
                <label className="block">
                  <span className="ui-label">{t('addProduct.variant')}</span>
                  <input
                    className="text-sm"
                    value={form.variant ?? ''}
                    onChange={e => setForm(f => ({ ...f, variant: e.target.value }))}
                    placeholder={t('addProduct.variantPlaceholder')}
                  />
                </label>
              </div>
              <div className="flex gap-2">
                <div className="flex-1">
                  <label className="block">
                    <span className="ui-label">{t('addProduct.packageSizeRequired')}</span>
                    <input
                      type="number"
                      min="0.01"
                      step="0.01"
                      className="text-sm"
                      value={form.packageSize}
                      onChange={e => setForm(f => ({ ...f, packageSize: parseFloat(e.target.value) || 0 }))}
                    />
                  </label>
                </div>
                <div className="w-[132px]">
                  <span className="ui-label">{t('addProduct.unitRequired')}</span>
                  <SearchableSelect label={t('addProduct.unit')}
                    value={form.unitOfMeasure}
                    onChange={v => setForm(f => ({ ...f, unitOfMeasure: v as UnitOfMeasure }))}
                    options={UNITS.map(u => ({ value: u, label: t(`units.${u}`) }))}
                  />
                </div>
              </div>
              <div>
                <label className="block">
                  <span className="ui-label">{t('fields.hsnCode')}</span>
                  <input
                    className="text-sm"
                    inputMode="numeric"
                    maxLength={8}
                    value={form.hsnCode ?? ''}
                    onChange={e => setForm(f => ({ ...f, hsnCode: e.target.value.replace(/\D/g, '') }))}
                    placeholder={t('addProduct.hsnPlaceholder')}
                  />
                </label>
              </div>
              <div>
                <label className="block">
                  <span className="ui-label">{t('addProduct.lowStockUnits')}</span>
                  <input
                    type="number"
                    min="0"
                    className="text-sm"
                    value={form.lowStockThreshold ?? ''}
                    onChange={e => setForm(f => ({ ...f, lowStockThreshold: e.target.value ? parseInt(e.target.value) : undefined }))}
                    placeholder={t('addProduct.lowStockPlaceholder')}
                  />
                </label>
              </div>
            </div>
          </>
        )}

        {step === 'review' && (
          <>
            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}
            <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
              <ReviewRow label={t('addProduct.name')} value={normalizedName} />
              <ReviewRow label={t('addProduct.brand')} value={normalizedBrand ?? '—'} />
              <ReviewRow label={t('addProduct.variant')} value={normalizedVariant ?? '—'} />
              <ReviewRow label={t('addProduct.packageSize')} value={String(form.packageSize)} />
              <ReviewRow label={t('addProduct.unit')} value={t(`units.${form.unitOfMeasure}`)} />
              <ReviewRow label={t('fields.hsnCode')} value={normalizedHsnCode ?? '—'} />
              <ReviewRow label={t('addProduct.lowStock')} value={normalizedThreshold != null ? t('addProduct.lowStockValue', { value: normalizedThreshold }) : t('addProduct.lowStockDisabled')} />
            </div>
          </>
        )}

    </Modal>
  )
}
