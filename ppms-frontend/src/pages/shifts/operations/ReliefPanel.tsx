import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import { userApi } from '../../../api/userApi'
import type { OperatorOnShift, Shift, ShiftRelief } from '../../../types/shift'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { parseApiError } from '../../../utils/apiError'
import { ReliefReadings } from './ReliefReadings'

interface Props {
  shift: Shift
  operators: OperatorOnShift[]
  reliefs: ShiftRelief[]
  /** The operator signed in, when an operator uses the panel; they can only start their own break. */
  ownOperatorId: number | null
  onChanged: () => void
}

/** Starts a break with a relief operator, or ends a running one, at the meter readings of the moment. */
export function ReliefPanel({ shift, operators, reliefs, ownOperatorId, onChanged }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const active = reliefs.find((relief) => relief.active) ?? null
  const working = operators.filter((operator) => operator.active && !operator.onBreak && operator.nozzleIds.length > 0)
  const [relievedId, setRelievedId] = useState(ownOperatorId !== null ? String(ownOperatorId) : '')
  const [reliefId, setReliefId] = useState('')
  const [readings, setReadings] = useState<Record<number, string>>({})
  const [cash, setCash] = useState('')
  const [error, setError] = useState<string | null>(null)

  const { data: pumpOperators = [] } = useQuery({
    queryKey: ['operators', shift.pumpId],
    queryFn: () => userApi.getOperators(shift.pumpId),
    enabled: ownOperatorId === null,
  })
  const relieverChoices = ownOperatorId === null
    ? pumpOperators.map((operator) => ({ id: operator.id, name: operator.fullName }))
    : operators.filter((operator) => operator.active).map((operator) => ({ id: operator.operatorId, name: operator.operatorName ?? '—' }))
  const relieved = operators.find((operator) => operator.operatorId === Number(relievedId))
  const coveredNozzles = active
    ? operators.find((operator) => operator.operatorId === active.reliefOperatorId)?.nozzleIds ?? []
    : relieved?.nozzleIds ?? []
  const readingList = coveredNozzles.map((nozzleId) => ({ nozzleId, meterReading: Number(readings[nozzleId]) }))
  const readingsComplete = coveredNozzles.length > 0 && coveredNozzles.every((nozzleId) => (readings[nozzleId] ?? '') !== '')
  const cashAmount = cash === '' ? undefined : Number(cash)

  const done = () => { setReadings({}); setCash(''); setReliefId(''); onChanged() }
  const start = useMutation({
    mutationFn: () => shiftApi.startRelief(shift.pumpId, shift.id, {
      relievedOperatorId: Number(relievedId), reliefOperatorId: Number(reliefId), readings: readingList, cashHandedOver: cashAmount,
    }),
    onSuccess: done,
    onError: (err: unknown) => setError(parseApiError(err, t('operations.relief.startFailed'))),
  })
  const end = useMutation({
    mutationFn: (running: ShiftRelief) => shiftApi.endRelief(shift.pumpId, shift.id, running.id, {
      readings: readingList, cashHandedBack: cashAmount,
    }),
    onSuccess: done,
    onError: (err: unknown) => setError(parseApiError(err, t('operations.relief.endFailed'))),
  })

  return (
    <section className="space-y-3" aria-labelledby={`relief-${shift.id}`}>
      <h3 id={`relief-${shift.id}`} className="ui-title-sm">{t('operations.relief.title')}</h3>
      {active ? (
        <p className="text-sm text-ink-2">
          {t('operations.relief.running', { relieved: active.relievedOperatorName ?? '—', reliever: active.reliefOperatorName ?? '—' })}
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="ui-label mb-0">{t('operations.relief.relieved')}</span>
            <select className="ui-input" value={relievedId} disabled={ownOperatorId !== null}
              onChange={(e) => { setRelievedId(e.target.value); setReadings({}) }}>
              <option value="">{t('operations.operators.choose')}</option>
              {working.map((operator) => <option key={operator.operatorId} value={operator.operatorId}>{operator.operatorName}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="ui-label mb-0">{t('operations.relief.reliever')}</span>
            <select className="ui-input" value={reliefId} onChange={(e) => setReliefId(e.target.value)}>
              <option value="">{t('operations.operators.choose')}</option>
              {relieverChoices.filter((operator) => String(operator.id) !== relievedId)
                .map((operator) => <option key={operator.id} value={operator.id}>{operator.name}</option>)}
            </select>
          </label>
        </div>
      )}
      <ReliefReadings shift={shift} nozzleIds={coveredNozzles} values={readings}
        onChange={(nozzleId, value) => setReadings((prev) => ({ ...prev, [nozzleId]: value }))} />
      <label className="flex flex-col gap-1 text-sm">
        <span className="ui-label mb-0">{active ? t('operations.relief.cashBack') : t('operations.relief.cashOver')}</span>
        <NumericInput step="0.01" min="0" value={cash} onChange={(e) => setCash(e.target.value)} />
      </label>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
      {active ? (
        <LoadingButton type="button" disabled={!readingsComplete} isLoading={end.isPending} loadingLabel={t('operations.saving')}
          className="ui-btn-primary" onClick={() => { setError(null); end.mutate(active) }}>
          {t('operations.relief.end')}
        </LoadingButton>
      ) : (
        <LoadingButton type="button" disabled={!readingsComplete || reliefId === ''} isLoading={start.isPending}
          loadingLabel={t('operations.saving')} className="ui-btn-primary" onClick={() => { setError(null); start.mutate() }}>
          {t('operations.relief.start')}
        </LoadingButton>
      )}
    </section>
  )
}
