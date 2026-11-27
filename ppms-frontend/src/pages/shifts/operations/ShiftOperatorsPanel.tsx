import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import { userApi } from '../../../api/userApi'
import type { OperatorOnShift, Shift } from '../../../types/shift'
import { LoadingButton } from '../../../components/LoadingButton'
import { StatusBadge } from '../../../components/StatusBadge'
import { parseApiError } from '../../../utils/apiError'

interface Props {
  shift: Shift
  operators: OperatorOnShift[]
  canManage: boolean
  onChanged: () => void
}

/** Who works the shift now and which nozzles each holds; a manager can add an operator or let an added one go. */
export function ShiftOperatorsPanel({ shift, operators, canManage, onChanged }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const [newOperatorId, setNewOperatorId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const nozzleLabel = (nozzleId: number) => `#${shift.nozzles.find((n) => n.id === nozzleId)?.nozzleNumber ?? '?'}`

  const { data: pumpOperators = [] } = useQuery({
    queryKey: ['operators', shift.pumpId],
    queryFn: () => userApi.getOperators(shift.pumpId),
    enabled: canManage,
  })
  const onShift = new Set(operators.filter((o) => o.active).map((o) => o.operatorId))
  const addable = pumpOperators.filter((operator) => !onShift.has(operator.id))

  const add = useMutation({
    mutationFn: () => shiftApi.addOperator(shift.pumpId, shift.id, Number(newOperatorId)),
    onSuccess: () => { setNewOperatorId(''); onChanged() },
    onError: (err: unknown) => setError(parseApiError(err, t('operations.operators.addFailed'))),
  })
  const remove = useMutation({
    mutationFn: (operatorId: number) => shiftApi.removeOperator(shift.pumpId, shift.id, operatorId),
    onSuccess: onChanged,
    onError: (err: unknown) => setError(parseApiError(err, t('operations.operators.removeFailed'))),
  })

  return (
    <section className="space-y-3" aria-labelledby={`shift-operators-${shift.id}`}>
      <h3 id={`shift-operators-${shift.id}`} className="ui-title-sm">{t('operations.operators.title')}</h3>
      <ul className="divide-y divide-border rounded-card border border-border">
        {operators.filter((operator) => operator.active).map((operator) => (
          <li key={`${operator.operatorId}-${operator.joinedAt}`} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
            <span className="min-w-0">
              <span className="block font-medium text-ink">{operator.operatorName ?? '—'}</span>
              <span className="text-xs text-ink-3">
                {operator.nozzleIds.length > 0
                  ? t('operations.operators.nozzles', { nozzles: operator.nozzleIds.map(nozzleLabel).join(', ') })
                  : t('operations.operators.noNozzles')}
              </span>
            </span>
            <span className="flex items-center gap-2">
              <StatusBadge tone="neutral" label={t(`operations.roles.${operator.role}`)} />
              {operator.onBreak && <StatusBadge tone="warn" label={t('operations.operators.onBreak')} />}
              {canManage && operator.role === 'ADDITIONAL' && (
                <button type="button" className="ui-text-action" disabled={remove.isPending}
                  onClick={() => { setError(null); remove.mutate(operator.operatorId) }}>
                  {t('operations.operators.remove')}
                </button>
              )}
            </span>
          </li>
        ))}
      </ul>
      {canManage && (
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex min-w-48 flex-1 flex-col gap-1 text-sm">
            <span className="ui-label mb-0">{t('operations.operators.addLabel')}</span>
            <select className="ui-input" value={newOperatorId} onChange={(e) => setNewOperatorId(e.target.value)}>
              <option value="">{t('operations.operators.choose')}</option>
              {addable.map((operator) => <option key={operator.id} value={operator.id}>{operator.fullName}</option>)}
            </select>
          </label>
          <LoadingButton type="button" disabled={newOperatorId === ''} isLoading={add.isPending}
            loadingLabel={t('operations.saving')} className="ui-btn-secondary"
            onClick={() => { setError(null); add.mutate() }}>
            {t('operations.operators.add')}
          </LoadingButton>
        </div>
      )}
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </section>
  )
}
