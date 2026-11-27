import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import { userApi } from '../../../api/userApi'
import type { OperatorOnShift, Shift } from '../../../types/shift'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { codeLabel } from '../../../constants/labels'
import { parseApiError } from '../../../utils/apiError'

interface Props {
  shift: Shift
  operators: OperatorOnShift[]
  onChanged: () => void
}

/** Moves one nozzle to another operator at the meter reading of the moment; the litres before it stay with the first. */
export function SwapNozzleForm({ shift, operators, onChanged }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const [nozzleId, setNozzleId] = useState('')
  const [toOperatorId, setToOperatorId] = useState('')
  const [reading, setReading] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)

  const { data: pumpOperators = [] } = useQuery({
    queryKey: ['operators', shift.pumpId],
    queryFn: () => userApi.getOperators(shift.pumpId),
  })
  const holder = operators.find((operator) => operator.active && operator.nozzleIds.includes(Number(nozzleId)))
  const choices = pumpOperators.filter((operator) => operator.id !== holder?.operatorId)

  const swap = useMutation({
    mutationFn: () => shiftApi.swapNozzle(shift.pumpId, shift.id, {
      nozzleId: Number(nozzleId),
      toOperatorId: Number(toOperatorId),
      meterReading: Number(reading),
      note: note.trim() || undefined,
    }),
    onSuccess: () => {
      setNozzleId('')
      setToOperatorId('')
      setReading('')
      setNote('')
      onChanged()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('operations.swap.failed'))),
  })

  return (
    <section className="space-y-3" aria-labelledby={`swap-${shift.id}`}>
      <h3 id={`swap-${shift.id}`} className="ui-title-sm">{t('operations.swap.title')}</h3>
      <p className="text-xs text-ink-3">{t('operations.swap.help')}</p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="ui-label mb-0">{t('operations.swap.nozzle')}</span>
          <select className="ui-input" value={nozzleId} onChange={(e) => setNozzleId(e.target.value)}>
            <option value="">{t('operations.operators.choose')}</option>
            {shift.nozzles.map((nozzle) => (
              <option key={nozzle.id} value={nozzle.id}>{`#${nozzle.nozzleNumber} ${codeLabel(nozzle.fuelType)}`}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="ui-label mb-0">{t('operations.swap.toOperator')}</span>
          <select className="ui-input" value={toOperatorId} onChange={(e) => setToOperatorId(e.target.value)}>
            <option value="">{t('operations.operators.choose')}</option>
            {choices.map((operator) => <option key={operator.id} value={operator.id}>{operator.fullName}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="ui-label mb-0">{t('operations.meterReading')}</span>
          <NumericInput step="0.001" min="0" value={reading} onChange={(e) => setReading(e.target.value)} />
        </label>
      </div>
      {holder && <p className="text-xs text-ink-3">{t('operations.swap.heldBy', { name: holder.operatorName ?? '—' })}</p>}
      <label className="flex flex-col gap-1 text-sm">
        <span className="ui-label mb-0">{t('operations.note')}</span>
        <input className="ui-input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
      <LoadingButton type="button" disabled={nozzleId === '' || toOperatorId === '' || reading === ''}
        isLoading={swap.isPending} loadingLabel={t('operations.saving')} className="ui-btn-primary"
        onClick={() => { setError(null); swap.mutate() }}>
        {t('operations.swap.save')}
      </LoadingButton>
    </section>
  )
}
