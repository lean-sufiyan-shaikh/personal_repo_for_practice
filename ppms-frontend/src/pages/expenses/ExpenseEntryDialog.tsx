import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { expenseApi } from '../../api/expenseApi'
import type { ExpenseCategory, ExpensePaidFrom, CreateExpenseRequest } from '../../api/expenseApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { NumericInput } from '../../components/NumericInput'
import { FormField } from '../../components/FormField'
import { StillNeeded } from '../../components/StillNeeded'
import { CATEGORIES, PAID_FROM } from './expenseFormat'
import { ExpenseReviewStep } from './ExpenseReviewStep'
import { attemptFor } from '../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../utils/idempotencyKey'
import { codeLabel } from '../../constants/labels'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'

interface ExpenseEntryDialogProps {
  pumpId: number
  onClose: () => void
}

type ExpenseForm = Omit<CreateExpenseRequest, 'paidFrom'> & { paidFrom: ExpensePaidFrom | '' }

interface FieldErrors {
  amount?: string
  description?: string
  expenseDate?: string
}

/** Records an expense: the entry form, then a review step before saving. */
export function ExpenseEntryDialog({ pumpId, onClose }: ExpenseEntryDialogProps) {
  const { t } = useTranslation(['expenses', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const categoryLabelId = useId()
  const paidFromLabelId = useId()
  const [today] = useState(localDateInputValue)
  const [form, setForm] = useState<ExpenseForm>({
    category: 'MAINTENANCE',
    amount: 0,
    description: '',
    expenseDate: today,
    paidFrom: '',
    saveDraft: false,
  })
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(null)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [serverError, setServerError] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)

  const createMutation = useMutation({
    mutationFn: ({ data, key }: { data: CreateExpenseRequest; key: string }) => expenseApi.createExpense(pumpId, data, key),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      qc.invalidateQueries({ queryKey: ['safe', pumpId] })
      qc.invalidateQueries({ queryKey: ['pettyCash', pumpId] })
      addToast(t('form.recorded'), 'success')
      onClose()
    },
    onError: (err) => {
      const msg = parseApiError(err, t('form.recordFailed'))
      setServerError(msg)
      addToast(msg, 'error')
    },
  })

  const validateForm = () => {
    const errors: FieldErrors = {}
    if (!form.description.trim()) errors.description = t('form.descriptionRequired')
    if (form.amount <= 0) errors.amount = t('form.amountPositive')
    if (form.expenseDate > today) errors.expenseDate = t('form.dateInFuture')
    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    if (validateForm()) setReviewing(true)
  }

  if (reviewing && form.paidFrom !== '') {
    const data: CreateExpenseRequest = { ...form, paidFrom: form.paidFrom }
    return (
      <ExpenseReviewStep form={data} isPending={createMutation.isPending} serverError={serverError}
        onBack={() => setReviewing(false)} onClose={onClose}
        onSave={() => {
          if (!validateForm()) return
          const attempt = attemptFor(submitAttempt, data)
          setSubmitAttempt(attempt)
          createMutation.mutate({ data, key: attempt.key })
        }} />
    )
  }

  const stillNeeded = [
    ...(form.amount > 0 ? [] : [t('form.needAmount')]),
    ...(form.description.trim() ? [] : [t('form.needDescription')]),
    ...(form.paidFrom ? [] : [t('form.needPaidFrom')]),
  ]

  return (
    <Modal
      tone="info"
      title={t('form.title')}
      subtitle={t('form.subtitle')}
      size="lg"
      onClose={onClose}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button type="submit" disabled={stillNeeded.length > 0}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          className="ui-btn ui-btn-primary">
          {t('common:actions.review')}
        </button>
      </>}
    >
      <FormGrid>
        <div>
          <span id={categoryLabelId} className="ui-label">{t('form.category')}</span>
          <SearchableSelect
            value={form.category}
            onChange={v => setForm(f => ({ ...f, category: v as ExpenseCategory }))}
            options={CATEGORIES.map(c => ({ value: c, label: codeLabel(c) }))}
            aria-labelledby={categoryLabelId}
          />
        </div>

        <FormField label={t('form.amount')} error={fieldErrors.amount}>
          <NumericInput
            unit="₹"
            min="0"
            step="0.01"
            value={form.amount || ''}
            onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) || 0 }))}
            data-autofocus
            required
          />
        </FormField>

        <FormField label={t('form.description')} error={fieldErrors.description}>
          <input
            type="text"
            value={form.description}
            onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
            required
          />
        </FormField>

        <div>
          <span id={paidFromLabelId} className="ui-label">{t('form.paidFrom')}</span>
          <SearchableSelect
            value={form.paidFrom}
            onChange={v => setForm(f => ({ ...f, paidFrom: v as ExpensePaidFrom }))}
            options={[{ value: '', label: t('form.choosePaidFrom') }, ...PAID_FROM.map(p => ({ value: p, label: codeLabel(p) }))]}
            aria-labelledby={paidFromLabelId}
          />
          <p className="mt-1 text-xs text-ink-3">{t('form.paidFromHint')}</p>
        </div>

        <FormField label={t('form.expenseDate')} error={fieldErrors.expenseDate}>
          <input
            type="date"
            max={today}
            value={form.expenseDate}
            onChange={e => setForm(f => ({ ...f, expenseDate: e.target.value }))}
            required
          />
        </FormField>

        <FormGridFullRow>
          <label className="ui-inline-check">
            <input
              type="checkbox"
              checked={!!form.saveDraft}
              onChange={e => setForm(f => ({ ...f, saveDraft: e.target.checked }))}
            />
            <span>{t('form.saveDraft')}</span>
          </label>
        </FormGridFullRow>
      </FormGrid>

      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
