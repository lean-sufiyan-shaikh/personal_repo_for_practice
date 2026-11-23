import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { expenseApi } from '../../api/expenseApi'
import type { ExpenseCategory, CreateExpenseRequest } from '../../api/expenseApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { Reveal } from '../../components/Reveal'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { CATEGORIES, fmtDate } from './expenseFormat'
import { codeLabel } from '../../constants/labels'

export function ExpenseEntryForm({ pumpId }: { pumpId: number | null }) {
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
  const [reviewOpen, setReviewOpen] = useState(false)

  const createMutation = useMutation({
    mutationFn: (data: CreateExpenseRequest) => expenseApi.createExpense(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      setForm({ category: 'MAINTENANCE', amount: 0, description: '', expenseDate: localDateInputValue(), saveDraft: false })
      setFormError(null)
      setReviewOpen(false)
      addToast('Expense recorded successfully', 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, 'Failed to record expense')
      setFormError(msg)
      addToast(msg, 'error')
    },
  })

  const validateForm = () => {
    if (!form.description.trim()) {
      setFormError('Description is required')
      return false
    }
    if (form.amount <= 0) {
      setFormError('Amount must be greater than zero')
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

  return (
    <>
      <Reveal delay={180}><form onSubmit={handleSubmit} className="ui-card ui-form-shell">
        <div className="ui-form-shell__head">
          <div>
            <p className="ui-section-kicker mb-2">Expense Intake</p>
            <h2 className="ui-title-sm">Record expense</h2>
            <p className="ui-subtitle mt-1">Capture the category, amount, date, and description before review.</p>
          </div>
        </div>

        <div className="ui-form-shell__grid">
          <div>
            <span id="expense-category-label" className="ui-label">Category</span>
            <SearchableSelect
              value={form.category}
              onChange={v => setForm(f => ({ ...f, category: v as ExpenseCategory }))}
              options={CATEGORIES.map(c => ({ value: c, label: codeLabel(c) }))}
              aria-labelledby="expense-category-label"
            />
          </div>

          <div>
            <label htmlFor="expense-amount" className="ui-label">Amount (₹)</label>
            <input
              id="expense-amount"
              type="number"
              min="0"
              step="0.01"
              value={form.amount || ''}
              onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) || 0 }))}
              className="text-sm"
              placeholder="0.00"
              required
            />
          </div>

          <div>
            <label htmlFor="expense-description" className="ui-label">Description</label>
            <input
              id="expense-description"
              type="text"
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              className="text-sm"
              placeholder="e.g. Pump servicing by XYZ"
              required
            />
          </div>

          <div>
            <label htmlFor="expense-date" className="ui-label">Expense Date</label>
            <input
              id="expense-date"
              type="date"
              value={form.expenseDate}
              onChange={e => setForm(f => ({ ...f, expenseDate: e.target.value }))}
              className="text-sm"
              required
            />
          </div>
        </div>

        <label className="flex items-center gap-2 text-sm text-ink-2 cursor-pointer w-fit">
          <input
            type="checkbox"
            checked={!!form.saveDraft}
            onChange={e => setForm(f => ({ ...f, saveDraft: e.target.checked }))}
            className="rounded-badge border-control text-primary"
          />
          Save as draft — submit for approval later
        </label>

        {formError && <p role="alert" className="ui-error-text">{formError}</p>}

        <button
          type="submit"
          disabled={createMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {createMutation.isPending
            ? <span className="flex items-center gap-1.5"><Spinner />Saving…</span>
            : 'Review Expense'}
        </button>
      </form></Reveal>

      {reviewOpen && (
        <Modal
          title="Review Expense"
          subtitle="Verify the expense details before saving the record."
          tone="warning"
          onClose={() => setReviewOpen(false)}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={<>
              <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-secondary">
                Back
              </button>
              <button
                onClick={() => {
                  if (!validateForm()) return
                  createMutation.mutate(form)
                }}
                disabled={createMutation.isPending}
                className="ui-btn ui-btn-primary"
              >
                {createMutation.isPending
                  ? <span className="flex items-center gap-1.5"><Spinner />Saving…</span>
                  : 'Add Expense'}
              </button>
          </>}
        >
              {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{formError} Go back to modify the data and try again.</div>}
              <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-ink-3 text-xs">Category</span>
                  <StatusBadge tone="neutral" label={codeLabel(form.category)} />
                </div>
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-ink-3 text-xs">Amount</span>
                  <MoneyText value={form.amount} className="font-medium text-sm text-ink" />
                </div>
                <div className="flex items-center justify-between gap-3 px-4 py-2.5">
                  <span className="text-ink-3 text-xs">Description</span>
                  <span className="font-medium text-right text-sm text-ink">{form.description.trim()}</span>
                </div>
                <div className="flex items-center justify-between px-4 py-2.5">
                  <span className="text-ink-3 text-xs">Expense Date</span>
                  <span className="font-medium text-sm text-ink">{fmtDate(form.expenseDate)}</span>
                </div>
              </div>
        </Modal>
      )}
    </>
  )
}
