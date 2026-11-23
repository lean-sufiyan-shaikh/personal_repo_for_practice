import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { cashApi } from '../../api/cashApi'
import type { CashEventType, RecordCashEventRequest } from '../../api/cashApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { Reveal } from '../../components/Reveal'
import { Spinner } from '../../components/Spinner'
import { NumericInput } from '../../components/NumericInput'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import type { ModalTone } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { parseApiError } from '../../utils/apiError'
import { EVENT_BADGES, EVENT_TYPES, fmtAmt, fmtDate } from './cashFormat'

interface CashEntryFormProps {
  pumpId: number | null
  balance: number
}

export function CashEntryForm({ pumpId, balance }: CashEntryFormProps) {
  const { t } = useTranslation('cash')
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [form, setForm] = useState<RecordCashEventRequest>({
    eventType: 'CASH_IN',
    amount: 0,
    description: '',
    eventDate: localDateInputValue(),
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)

  const createMutation = useMutation({
    mutationFn: (data: RecordCashEventRequest) => cashApi.recordCashEvent(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cashEvents', pumpId] })
      setForm({ eventType: 'CASH_IN', amount: 0, description: '', eventDate: localDateInputValue() })
      setFormError(null)
      setReviewOpen(false)
      addToast(t('form.recorded'), 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, t('form.recordFailed'))
      setFormError(msg)
      addToast(msg, 'error')
    },
  })

  const validateForm = () => {
    if (!form.description.trim()) {
      setFormError(t('form.descriptionRequired'))
      return false
    }
    if (form.amount <= 0) {
      setFormError(t('form.amountPositive'))
      return false
    }
    if (form.eventType === 'CASH_OUT' && form.amount > balance) {
      setFormError(t('form.exceedsBalance', { amount: fmtAmt(form.amount), balance: fmtAmt(balance) }))
      return false
    }
    setFormError(null)
    return true
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!validateForm()) return
    setReviewOpen(true)
  }

  const reviewHeaderTone: ModalTone =
    form.eventType === 'CASH_OUT'
      ? 'danger'
      : form.eventType === 'CASH_IN'
        ? 'success'
        : form.eventType === 'OPENING_BALANCE'
          ? 'info'
          : 'neutral'

  return (
    <>
      {/* ── Add event form ── */}
      <Reveal delay={200}>
      <form onSubmit={handleSubmit} className="ui-card ui-form-shell">
        <div className="ui-form-shell__head">
          <div>
            <p className="ui-section-kicker mb-2">{t('form.kicker')}</p>
            <h2 className="ui-title-sm">{t('form.title')}</h2>
            <p className="ui-subtitle mt-1">{t('form.subtitle')}</p>
          </div>
        </div>

        <div className="ui-form-shell__grid">
          <div>
            <span className="ui-label">{t('form.eventType')}</span>
            <SearchableSelect label={t('form.eventType')}
              value={form.eventType}
              onChange={v => setForm(f => ({ ...f, eventType: v as CashEventType }))}
              options={EVENT_TYPES.map(type => ({ value: type, label: t(`eventTypes.${type}`) }))}
            />
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('form.amountRupees')}</span>
              <NumericInput
                min="0"
                step="0.01"
                value={form.amount || ''}
                onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) || 0 }))}
                className={`text-sm ${form.eventType === 'CASH_OUT' && form.amount > balance ? 'border-bad focus:ring-bad' : ''}`}
                placeholder="0.00"
                required
              />
            </label>
            {form.eventType === 'CASH_OUT' && (
              <p className={`mt-1 text-xs ${form.amount > balance ? 'text-bad font-medium' : 'text-ink-3'}`}>
                {t('form.availableBalance', { amount: fmtAmt(balance) })}
              </p>
            )}
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('form.description')}</span>
              <input
                type="text"
                value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                className="text-sm"
                placeholder={t('form.descriptionPlaceholder')}
                required
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('form.date')}</span>
              <input
                type="date"
                value={form.eventDate}
                onChange={e => setForm(f => ({ ...f, eventDate: e.target.value }))}
                className="text-sm"
                required
              />
            </label>
          </div>
        </div>

        {formError && <p role="alert" className="ui-error-text">{formError}</p>}

        <button
          type="submit"
          disabled={createMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {createMutation.isPending
          ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('form.saving')}</span>
          : t('form.review')}
        </button>
      </form>
      </Reveal>

      {reviewOpen && (
        <Modal
          title={t('review.title')}
          subtitle={t('review.subtitle')}
          tone={reviewHeaderTone}
          onClose={() => setReviewOpen(false)}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={<>
            <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-secondary">
              {t('review.back')}
            </button>
            <button
              onClick={() => {
                if (!validateForm()) {
                  return
                }
                createMutation.mutate(form)
              }}
              disabled={createMutation.isPending}
              className="ui-btn ui-btn-primary"
            >
              {createMutation.isPending ? t('form.saving') : t('review.record')}
            </button>
          </>}
        >
          {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('review.goBack', { error: formError })}</div>}
          <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-ink-3 text-xs">{t('form.eventType')}</span>
              <StatusBadge
                {...EVENT_BADGES[form.eventType]}
                label={t(`eventTypes.${form.eventType}`)}
              />
            </div>
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-ink-3 text-xs">{t('form.amount')}</span>
              <MoneyText value={form.amount} className="font-medium text-sm text-ink" />
            </div>
            <div className="flex items-center justify-between gap-3 px-4 py-2.5">
              <span className="text-ink-3 text-xs">{t('form.description')}</span>
              <span className="font-medium text-right text-sm text-ink">{form.description.trim()}</span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-ink-3 text-xs">{t('form.date')}</span>
              <span className="font-medium text-sm text-ink">{fmtDate(form.eventDate)}</span>
            </div>
          </div>
        </Modal>
      )}
    </>
  )
}
