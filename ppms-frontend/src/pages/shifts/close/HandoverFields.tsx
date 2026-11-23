import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { userApi } from '../../../api/userApi'
import type { Shift } from '../../../types/shift'
import { OperatorPicker } from '../components/OperatorPicker'
import { Section } from './CloseShiftFields'
import type { HandoverDetails } from './handoverDetails'

interface Props {
  shift: Shift
  activeShifts: Shift[]
  details: HandoverDetails
  onChange: (details: HandoverDetails) => void
}

export function HandoverFields({ shift, activeShifts, details, onChange }: Props) {
  const [operatorSearch, setOperatorSearch] = useState('')

  const { data: operators = [], isLoading: operatorsLoading } = useQuery({
    queryKey: ['operators', shift.pumpId],
    queryFn:  () => userApi.getOperators(shift.pumpId),
  })

  const busyOperatorIds = new Set(activeShifts.map((s) => s.operatorId))
  const availableOperators = operators.filter((o) => o.id !== shift.operatorId && !busyOperatorIds.has(o.id))
  const filteredOperators = availableOperators.filter((o) =>
    o.fullName.toLowerCase().includes(operatorSearch.toLowerCase())
  )

  return (
    <Section label="0" title="Incoming Operator">
      <div className="space-y-3">
        <p className="rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-800">
          {shift.operatorName}&apos;s shift is closed with the readings and payments below, and a new shift opens
          for the incoming operator on the same nozzles from these closing readings.
        </p>
        <OperatorPicker
          showStepNumber={false}
          operatorsLoading={operatorsLoading}
          noneAvailable={availableOperators.length === 0}
          filteredOperators={filteredOperators}
          operatorSearch={operatorSearch}
          onSearchChange={setOperatorSearch}
          selectedOperatorId={details.incomingOperatorId}
          onSelect={(operatorId) => onChange({ ...details, incomingOperatorId: operatorId })}
        />
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" checked={details.physicalCashVerified}
            onChange={(e) => onChange({ ...details, physicalCashVerified: e.target.checked })} />
          Cash counted with the incoming operator
        </label>
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" checked={details.meterReadingsVerified}
            onChange={(e) => onChange({ ...details, meterReadingsVerified: e.target.checked })} />
          Meter readings checked together
        </label>
        <label className="block">
          <span className="ui-label">Handover notes (optional)</span>
          <input type="text" value={details.notes} maxLength={1000}
            onChange={(e) => onChange({ ...details, notes: e.target.value })}
            className="text-sm" placeholder="e.g. Lunch break" />
        </label>
      </div>
    </Section>
  )
}
