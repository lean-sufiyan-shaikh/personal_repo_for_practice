import { useState } from 'react'
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
      addToast('Cash event recorded successfully', 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, 'Failed to record event')
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
    if (form.eventType === 'CASH_OUT' && form.amount > balance) {
      setFormError(`Cash-out amount ${fmtAmt(form.amount)} exceeds the current drawer balance of ${fmtAmt(balance)}.`)
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
            <p className="ui-section-kicker mb-2">Cash Entry</p>
            <h2 className="ui-title-sm">Record cash movement</h2>
            <p className="ui-subtitle mt-1">Log money entering or leaving the safe with the right event type and date.</p>
          </div>
        </div>

        <div className="ui-form-shell__grid">
          <div>
            <span className="ui-label">Event Type</span>
            <SearchableSelect label="Event Type"
              value={form.eventType}
              onChange={v => setForm(f => ({ ...f, eventType: v as CashEventType }))}
              options={EVENT_TYPES.map(t => ({ value: t.value, label: t.label }))}
            />
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Amount (₹)</span>
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
                Available balance: {fmtAmt(balance)}
              </p>
            )}
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Description</span>
              <input
                type="text"
                value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                className="text-sm"
                placeholder="e.g. Shift 1 cash deposited"
                required
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Date</span>
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
          ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />Saving…</span>
          : 'Review Event'}
        </button>
      </form>
      </Reveal>

      {reviewOpen && (
        <Modal
          title="Review Cash Event"
          subtitle="Check the event details before recording it."
          tone={reviewHeaderTone}
          onClose={() => setReviewOpen(false)}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={<>
            <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-secondary">
              Back
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
              {createMutation.isPending ? 'Saving…' : 'Record Event'}
            </button>
          </>}
        >
          {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{formError} Go back to modify the data and try again.</div>}
          <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-ink-3 text-xs">Event Type</span>
              <StatusBadge
                {...EVENT_BADGES[form.eventType]}
                label={EVENT_TYPES.find((eventType) => eventType.value === form.eventType)?.label ?? form.eventType}
              />
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
              <span className="text-ink-3 text-xs">Date</span>
              <span className="font-medium text-sm text-ink">{fmtDate(form.eventDate)}</span>
            </div>
          </div>
        </Modal>
      )}
    </>
  )
}
