import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import type { ResolveDiscrepancyRequest } from '../../../api/shiftApi'
import type { Shift } from '../../../types/shift'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { parseApiError } from '../../../utils/apiError'
import { Modal } from '../../../components/Modal'
import { formatCurrencyOrDash } from '../../../utils/format'
import { investigationLabel, resolutionChoices } from '../shiftLabels'

export function ResolveDiscrepancyModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const { t } = useTranslation(['shifts', 'common'])
  const qc = useQueryClient()
  const [action, setAction] = useState<ResolveDiscrepancyRequest['resolutionAction'] | ''>('')
  const [note,   setNote]   = useState('')
  const [error,  setError]  = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: ResolveDiscrepancyRequest) => shiftApi.resolveDiscrepancy(shift.pumpId, shift.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shiftHistory', shift.pumpId] })
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('resolveDiscrepancy.failed'))),
  })

  const noteRequired = action === 'WAIVED' || action === 'PENDING_INVESTIGATION'

  const handleSubmit = () => {
    if (!action) {
      setError(t('resolveDiscrepancy.chooseAction'))
      return
    }
    if (action === 'WAIVED' && !note.trim()) {
      setError(t('resolveDiscrepancy.waiveReason'))
      return
    }
    if (action === 'PENDING_INVESTIGATION' && !note.trim()) {
      setError(t('resolveDiscrepancy.describeInvestigation'))
      return
    }
    setError(null)
    mutation.mutate({ resolutionAction: action, resolutionNote: note.trim() || undefined })
  }

  const discType  = shift.discrepancyType
  const discAmt   = shift.discrepancyAmount
  const investigation = investigationLabel(shift)
  // Salary deduction and cash recovery both take money back from the operator, so they only apply to a shortage.
  const resolutionOptions = discType === 'SHORT'
    ? resolutionChoices()
    : resolutionChoices().filter(option => option.value !== 'SALARY_DEDUCTION' && option.value !== 'CASH_RECOVERY')
  const discTypeText = discType === 'SHORT' ? t('common:badges.short') : discType === 'OVER' ? t('common:badges.over') : discType

  return (
    <tr>
      <td colSpan={8} className="p-0">
        <Modal
          title={t('resolveDiscrepancy.title')}
          subtitle={t('resolveDiscrepancy.subtitle', { id: shift.id, operator: shift.operatorName, type: discTypeText, amount: formatCurrencyOrDash(discAmt) })}
          headerExtra={shift.discrepancyReason && (
            <p className="ui-modal-subtitle">{t('resolveDiscrepancy.reasonAtClose', { reason: shift.discrepancyReason })}</p>
          )}
          tone="danger"
          size="md"
          onClose={onClose}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={
            <>
              <button onClick={onClose} className="ui-btn ui-btn-secondary flex-1">
                {t('common:actions.cancel')}
              </button>
              <button
                onClick={handleSubmit}
                disabled={mutation.isPending || !action}
                className="ui-btn ui-btn-danger flex-1"
              >
                {mutation.isPending ? t('common:actions.saving') : action === 'PENDING_INVESTIGATION' ? t('resolveDiscrepancy.startInvestigation') : t('resolveDiscrepancy.confirm')}
              </button>
            </>
          }
        >
          {investigation && (
            <p className="ui-alert ui-alert-warning text-xs">
              {investigation}{shift.investigationNote ? ` — ${shift.investigationNote}` : ''}
            </p>
          )}
          <div>
            <span className="ui-label">{t('resolveDiscrepancy.actionLabel')}</span>
            <SearchableSelect label={t('resolveDiscrepancy.actionLabel')}
              value={action}
              onChange={(v) => setAction(v as ResolveDiscrepancyRequest['resolutionAction'])}
              options={resolutionOptions}
              placeholder={t('resolveDiscrepancy.choosePlaceholder')}
            />
            {action === 'PENDING_INVESTIGATION' && (
              <p className="text-xs text-ink-3 mt-1">
                {t('resolveDiscrepancy.investigationHint')}
              </p>
            )}
          </div>
          <div>
            <label className="block">
              <span className="ui-label">
                {t('resolveDiscrepancy.note')} {noteRequired && <span className="text-bad">{t('resolveDiscrepancy.required')}</span>}
              </span>
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
                placeholder={t('resolveDiscrepancy.notePlaceholder')}
                className="resize-none text-sm"
              />
            </label>
          </div>
          {error && <p role="alert" className="ui-error-text">{error}</p>}
        </Modal>
      </td>
    </tr>
  )
}
