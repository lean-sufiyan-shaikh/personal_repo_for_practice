import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { expenseApi } from '../../api/expenseApi'
import type { ExpenseCategory, CreateExpenseRequest } from '../../api/expenseApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { CATEGORIES, fmtDate } from './expenseFormat'
import { codeLabel } from '../../constants/labels'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'

interface ExpenseEntryDialogProps {
  pumpId: number
  onClose: () => void
}

/** Records an expense: the entry form, then a review step before saving. */
export function ExpenseEntryDialog({ pumpId, onClose }: ExpenseEntryDialogProps) {
  const { t } = useTranslation(['expenses', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [form, setForm] = useState<CreateExpenseRequest>({
    category: 'MAINTENANCE',
    amount: 0,
    description: '',
    expenseDate: localDateInputValue(),
    saveDraft: false,
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)

  const createMutation = useMutation({
    mutationFn: (data: CreateExpenseRequest) => expenseApi.createExpense(pumpId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
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
        tone="warning"
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
            {createMutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner />{t('common:actions.saving')}</span>
              : t('review.add')}
          </button>
        </>}
      >
        {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('common:notices.goBackRetry', { error: formError })}</div>}
        <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
          <div className="flex items-center justify-between px-4 py-2.5">
            <span className="text-ink-3 text-xs">{t('form.category')}</span>
            <StatusBadge tone="neutral" label={codeLabel(form.category)} />
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
            <span className="text-ink-3 text-xs">{t('form.expenseDate')}</span>
            <span className="font-medium text-sm text-ink">{fmtDate(form.expenseDate)}</span>
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
          <span id="expense-category-label" className="ui-label">{t('form.category')}</span>
          <SearchableSelect
            value={form.category}
            onChange={v => setForm(f => ({ ...f, category: v as ExpenseCategory }))}
            options={CATEGORIES.map(c => ({ value: c, label: codeLabel(c) }))}
            aria-labelledby="expense-category-label"
          />
        </div>

        <div>
          <label htmlFor="expense-amount" className="ui-label">{t('form.amountRupees')}</label>
          <input
            id="expense-amount"
            type="number"
            min="0"
            step="0.01"
            value={form.amount || ''}
            onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) || 0 }))}
            className="text-sm"
            placeholder="0.00"
            data-autofocus
            required
          />
        </div>

        <div>
          <label htmlFor="expense-description" className="ui-label">{t('form.description')}</label>
          <input
            id="expense-description"
            type="text"
            value={form.description}
            onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
            className="text-sm"
            placeholder={t('form.descriptionPlaceholder')}
            required
          />
        </div>

        <div>
          <label htmlFor="expense-date" className="ui-label">{t('form.expenseDate')}</label>
          <input
            id="expense-date"
            type="date"
            value={form.expenseDate}
            onChange={e => setForm(f => ({ ...f, expenseDate: e.target.value }))}
            className="text-sm"
            required
          />
        </div>

        <FormGridFullRow>
          <label className="flex items-center gap-2 text-sm text-ink-2 cursor-pointer w-fit">
            <input
              type="checkbox"
              checked={!!form.saveDraft}
              onChange={e => setForm(f => ({ ...f, saveDraft: e.target.checked }))}
              className="rounded-badge border-control text-primary"
            />
            {t('form.saveDraft')}
          </label>
        </FormGridFullRow>
      </FormGrid>

      {formError && <p role="alert" className="ui-error-text">{formError}</p>}
    </Modal>
  )
}
