import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { StaffMember } from '../../api/userApi'
import { payrollApi } from '../../api/payrollApi'
import type { GeneratePayrollRequest } from '../../api/payrollApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Reveal } from '../../components/Reveal'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { ReviewModal } from './ReviewModal'
import { todayIso, yesterdayIso } from './payrollFormat'
import { codeLabel } from '../../constants/labels'

interface GeneratePayrollFormProps {
  pumpId: number | null
  staff: StaffMember[]
  onGenerated: () => void
}

export function GeneratePayrollForm({ pumpId, staff, onGenerated }: GeneratePayrollFormProps) {
  const { t } = useTranslation('payroll')
  const { addToast } = useToastStore()
  const qc = useQueryClient()
  const [form, setForm] = useState<GeneratePayrollRequest>(() => ({
    userId: 0,
    periodFrom: yesterdayIso(),
    periodTo: todayIso(),
    notes: null,
  }))
  const [formError, setFormError] = useState<string | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)

  const generateMutation = useMutation({
    mutationFn: (data: GeneratePayrollRequest) => payrollApi.generatePayroll(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payroll', pumpId] })
      setForm({ userId: 0, periodFrom: yesterdayIso(), periodTo: todayIso(), notes: null })
      setFormError(null)
      setReviewOpen(false)
      onGenerated()
      addToast(t('generate.generated'), 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, t('generate.failed'))
      setFormError(msg)
      addToast(msg, 'error')
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.userId) { setFormError(t('generate.staffRequired')); return }
    if (!form.periodFrom || !form.periodTo) { setFormError(t('generate.periodRequired')); return }
    setFormError(null)
    setReviewOpen(true)
  }

  const selectedStaff = staff.find((s) => s.id === form.userId)

  return (
    <>
      {/* ── Generate form ── */}
      <Reveal delay={130}>
      <form onSubmit={handleSubmit} className="ui-card ui-form-shell">
        <div className="ui-form-shell__head">
          <div>
            <p className="ui-section-kicker mb-2">{t('generate.kicker')}</p>
            <h2 className="ui-title-sm">{t('generate.title')}</h2>
            <p className="ui-subtitle mt-1">{t('generate.subtitle')}</p>
          </div>
        </div>

        <div className="ui-form-shell__grid">
          <div>
            <span className="ui-label">{t('generate.staffMember')}</span>
            <SearchableSelect label={t('generate.staffMember')}
              value={form.userId ? form.userId.toString() : ''}
              onChange={v => setForm(f => ({ ...f, userId: parseInt(v) || 0 }))}
              placeholder={t('generate.selectStaff')}
              options={staff.map((s) => ({ value: s.id.toString(), label: `${s.fullName} — ${codeLabel(s.role)}` }))}
            />
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('generate.periodFrom')}</span>
              <input
                type="date"
                value={form.periodFrom}
                onChange={e => setForm(f => ({ ...f, periodFrom: e.target.value }))}
                className="text-sm"
                required
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('generate.periodTo')}</span>
              <input
                type="date"
                value={form.periodTo}
                onChange={e => setForm(f => ({ ...f, periodTo: e.target.value }))}
                className="text-sm"
                required
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('generate.notes')}</span>
              <input
                type="text"
                value={form.notes ?? ''}
                onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
                className="text-sm"
                placeholder={t('generate.notesPlaceholder')}
              />
            </label>
          </div>
        </div>

        <p className="text-xs text-ink-3">
          {t('generate.howCalculated')}
        </p>

        {formError && <p role="alert" className="ui-error-text">{formError}</p>}

        <button
          type="submit"
          disabled={generateMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {generateMutation.isPending
            ? <span className="flex items-center gap-1.5"><Spinner />{t('generate.calculating')}</span>
            : t('generate.review')}
        </button>
      </form>
      </Reveal>

      {reviewOpen && form.userId > 0 && (
        <ReviewModal
          form={form}
          selectedStaff={selectedStaff}
          pumpId={pumpId!}
          formError={formError}
          isPending={generateMutation.isPending}
          onClose={() => setReviewOpen(false)}
          onGenerate={(deductIds) => generateMutation.mutate({ ...form, deductFromSalaryShiftIds: deductIds })}
        />
      )}
    </>
  )
}
