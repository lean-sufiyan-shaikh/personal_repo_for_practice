import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { StaffMember } from '../../api/userApi'
import { payrollApi } from '../../api/payrollApi'
import type { GeneratePayrollRequest } from '../../api/payrollApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Modal } from '../../components/Modal'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { ReviewModal } from './ReviewModal'
import { todayIso, yesterdayIso } from './payrollFormat'
import { codeLabel } from '../../constants/labels'

interface GeneratePayrollDialogProps {
  pumpId: number
  staff: StaffMember[]
  onGenerated: () => void
  onClose: () => void
}

/** Calculates a draft salary record: staff and period first, then the review step with shortfall deductions. */
export function GeneratePayrollDialog({ pumpId, staff, onGenerated, onClose }: GeneratePayrollDialogProps) {
  const { t } = useTranslation(['payroll', 'common'])
  const { addToast } = useToastStore()
  const qc = useQueryClient()
  const [form, setForm] = useState<GeneratePayrollRequest>(() => ({
    userId: 0,
    periodFrom: yesterdayIso(),
    periodTo: todayIso(),
    notes: null,
  }))
  const [formError, setFormError] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)

  const generateMutation = useMutation({
    mutationFn: (data: GeneratePayrollRequest) => payrollApi.generatePayroll(pumpId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payroll', pumpId] })
      onGenerated()
      addToast(t('generate.generated'), 'success')
      onClose()
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
    setReviewing(true)
  }

  if (reviewing && form.userId > 0) {
    return (
      <ReviewModal
        form={form}
        selectedStaff={staff.find((s) => s.id === form.userId)}
        pumpId={pumpId}
        formError={formError}
        isPending={generateMutation.isPending}
        onClose={() => setReviewing(false)}
        onGenerate={(deductIds) => generateMutation.mutate({ ...form, deductFromSalaryShiftIds: deductIds })}
      />
    )
  }

  return (
    <Modal
      title={t('generate.title')}
      subtitle={t('generate.subtitle')}
      size="lg"
      onClose={onClose}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button type="submit" className="ui-btn ui-btn-primary">
          {t('generate.review')}
        </button>
      </>}
    >
      <FormGrid>
        <FormGridFullRow>
          <span className="ui-label">{t('generate.staffMember')}</span>
          <SearchableSelect label={t('generate.staffMember')}
            value={form.userId ? form.userId.toString() : ''}
            onChange={v => setForm(f => ({ ...f, userId: parseInt(v) || 0 }))}
            placeholder={t('generate.selectStaff')}
            options={staff.map((s) => ({ value: s.id.toString(), label: `${s.fullName} — ${codeLabel(s.role)}` }))}
          />
        </FormGridFullRow>

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

        <FormGridFullRow>
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
        </FormGridFullRow>
      </FormGrid>

      <p className="text-xs text-ink-3">
        {t('generate.howCalculated')}
      </p>

      {formError && <p role="alert" className="ui-error-text">{formError}</p>}
    </Modal>
  )
}
