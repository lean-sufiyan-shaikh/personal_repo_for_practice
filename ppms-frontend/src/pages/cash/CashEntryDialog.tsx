import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { cashApi } from '../../api/cashApi'
import type { CashEventType, RecordCashEventRequest } from '../../api/cashApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { NumericInput } from '../../components/NumericInput'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import type { ModalTone } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { FormGrid } from '../../components/layout/FormGrid'
import { parseApiError } from '../../utils/apiError'
import { EVENT_BADGES, EVENT_TYPES, fmtAmt, fmtDate } from './cashFormat'

interface CashEntryDialogProps {
  pumpId: number
  balance: number
  onClose: () => void
}

const REVIEW_TONE: Record<CashEventType, ModalTone> = {
  CASH_OUT: 'danger',
  CASH_IN: 'success',
  OPENING_BALANCE: 'info',
  CLOSING_BALANCE: 'neutral',
}

/** Records money entering or leaving the safe: the entry form, then a review step before saving. */
export function CashEntryDialog({ pumpId, balance, onClose }: CashEntryDialogProps) {
  const { t } = useTranslation(['cash', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [form, setForm] = useState<RecordCashEventRequest>({
    eventType: 'CASH_IN',
    amount: 0,
    description: '',
    eventDate: localDateInputValue(),
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)

  const createMutation = useMutation({
    mutationFn: (data: RecordCashEventRequest) => cashApi.recordCashEvent(pumpId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cashEvents', pumpId] })
      addToast(t('form.recorded'), 'success')
      onClose()
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
    if (validateForm()) setReviewing(true)
  }

  if (reviewing) {
    return (
      <Modal
        title={t('review.title')}
        subtitle={t('review.subtitle')}
        tone={REVIEW_TONE[form.eventType]}
        onClose={onClose}
        canClose={!createMutation.isPending}
        bodyClassName="space-y-4"
        footer={<>
          <button type="button" onClick={() => setReviewing(false)} disabled={createMutation.isPending} className="ui-btn ui-btn-secondary">
            {t('common:actions.back')}
          </button>
          <button
            type="button"
            onClick={() => { if (validateForm()) createMutation.mutate(form) }}
            disabled={createMutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {createMutation.isPending ? t('common:actions.saving') : t('review.record')}
          </button>
        </>}
      >
        {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('common:notices.goBackRetry', { error: formError })}</div>}
        <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
          <div className="flex items-center justify-between px-4 py-2.5">
            <span className="text-ink-3 text-xs">{t('form.eventType')}</span>
            <StatusBadge {...EVENT_BADGES[form.eventType]} label={t(`eventTypes.${form.eventType}`)} />
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
    )
  }

  return (
    <Modal
      title={t('form.title')}
      subtitle={t('form.subtitle')}
      size="lg"
      onClose={onClose}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button type="submit" className="ui-btn ui-btn-primary">
          {t('form.review')}
        </button>
      </>}
    >
      <FormGrid>
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
              data-autofocus
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
      </FormGrid>

      {formError && <p role="alert" className="ui-error-text">{formError}</p>}
    </Modal>
  )
}
