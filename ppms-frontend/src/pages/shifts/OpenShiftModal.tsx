import { useState, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Lock } from 'lucide-react'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { userApi } from '../../api/userApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import type { DUOption, NozzleDetail, OpenShiftRequest, Shift } from '../../types/shift'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { parseApiError } from '../../utils/apiError'
import { FUEL_LABELS, FUEL_UNIT } from '../../constants/fuel'
import { OperatorPicker } from './components/OperatorPicker'
import { useToastStore } from '../../store/toastStore'

/** Stored end times are inclusive (08:59 means the shift runs until 09:00), so the end minute is inside the window. */
function isTimeInWindow(startTime: string, endTime: string, crossesMidnight: boolean, nowHHMM: string): boolean {
  const start = startTime.substring(0, 5)
  const end   = endTime.substring(0, 5)
  if (crossesMidnight) {
    return nowHHMM >= start || nowHHMM <= end
  }
  return nowHHMM >= start && nowHHMM <= end
}

const IST_TIME_FORMAT = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})



interface Props {
  pumpId: number
  activeShifts: Shift[]
  onClose: () => void
  prefilledDuId?: number
  prefilledNozzleIds?: number[]
}

export default function OpenShiftModal({ pumpId, activeShifts, onClose, prefilledDuId, prefilledNozzleIds }: Props) {
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()

  const [selectedDU, setSelectedDU]                 = useState<DUOption | null>(null)
  const [selectedNozzleIds, setSelectedNozzleIds]   = useState<Set<number>>(new Set())
  const [selectedOperatorId, setSelectedOperatorId] = useState<number | null>(null)
  const [operatorSearch, setOperatorSearch]         = useState('')
  const [readingConfirmed, setReadingConfirmed]     = useState(false)
  const [validationError, setValidationError]       = useState<string | null>(null)
  const [serverError, setServerError]               = useState<string | null>(null)

  // Nozzle IDs already in an open shift (locked out)
  const busyNozzleIds   = new Set(activeShifts.flatMap((s) => s.nozzles.map((n) => n.id)))
  const busyOperatorIds = new Set(activeShifts.map((s) => s.operatorId))

  const { data: dus = [], isLoading: dusLoading } = useQuery({
    queryKey: ['dus', pumpId],
    queryFn:  () => pumpApi.getDUs(pumpId),
  })

  const { data: operators = [], isLoading: operatorsLoading } = useQuery({
    queryKey: ['operators', pumpId],
    queryFn:  () => userApi.getOperators(pumpId),
  })

  // Auto-select the DU and nozzles of a just-closed shift, or the only DU and its free nozzles
  useEffect(() => {
    if (!dus.length || selectedDU) return
    const activeDus = dus.filter(d => d.status === 'ACTIVE')
    const prefilledDu = prefilledDuId ? activeDus.find(d => d.id === prefilledDuId) : undefined
    if (prefilledDu) {
      setSelectedDU(prefilledDu)
      setSelectedNozzleIds(new Set(
        (prefilledNozzleIds ?? []).filter(id => prefilledDu.nozzles.some(n => n.id === id) && !busyNozzleIds.has(id))
      ))
      return
    }
    if (activeDus.length !== 1) return
    const onlyDu = activeDus[0]
    setSelectedDU(onlyDu)
    setSelectedNozzleIds(new Set(
      onlyDu.nozzles.filter(n => n.status === 'ACTIVE' && !busyNozzleIds.has(n.id)).map(n => n.id)
    ))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dus])

  const { data: activeDefinitions = [] } = useQuery({
    queryKey: ['shift-definitions-active', pumpId],
    queryFn:  () => shiftDefinitionApi.getActive(pumpId),
  })

  const nowHHMM = IST_TIME_FORMAT.format(new Date())
  const currentDefinition = activeDefinitions.find(d =>
    isTimeInWindow(d.startTime, d.endTime, d.crossesMidnight, nowHHMM)
  )
  const isNightShift = currentDefinition?.isNightShift ?? false

  const openMutation = useMutation({
    mutationFn: (req: OpenShiftRequest) => shiftApi.openShift(pumpId, req),
    onSuccess: (shift) => {
      queryClient.invalidateQueries({ queryKey: ['activeShifts', pumpId] })
      addToast(`Shift opened for ${shift.operatorName}`, 'success')
      onClose()
    },
    onError: (err) =>
      setServerError(parseApiError(err, 'Failed to open shift. Please try again.')),
  })

  // Only ACTIVE DUs are available for shift open
  const activeDUs = dus.filter((d) => d.status === 'ACTIVE')

  const availableOperators = operators.filter((o) => {
    if (busyOperatorIds.has(o.id)) return false
    if (isNightShift && o.gender === 'FEMALE' && !o.nightShiftConsent) return false
    return true
  })
  const filteredOperators = availableOperators.filter((o) =>
    o.fullName.toLowerCase().includes(operatorSearch.toLowerCase())
  )

  const handleSelectDU = (du: DUOption) => {
    if (selectedDU?.id === du.id) return
    setSelectedDU(du)
    setSelectedNozzleIds(new Set())
    setReadingConfirmed(false)
    setValidationError(null)
  }

  const handleNozzleToggle = (nozzle: NozzleDetail) => {
    if (busyNozzleIds.has(nozzle.id)) return
    setReadingConfirmed(false)
    setSelectedNozzleIds((prev) => {
      const next = new Set(prev)
      if (next.has(nozzle.id)) next.delete(nozzle.id)
      else next.add(nozzle.id)
      return next
    })
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setValidationError(null)
    setServerError(null)

    if (!selectedDU)                    { setValidationError('Please select a Dispensary Unit'); return }
    if (selectedNozzleIds.size === 0)   { setValidationError('Please select at least one nozzle'); return }
    if (!selectedOperatorId)            { setValidationError('Please select an operator'); return }
    if (!readingConfirmed)              { setValidationError('Please confirm the meter readings are correct'); return }

    const req: OpenShiftRequest = {
      duId:       selectedDU.id,
      nozzleIds:  Array.from(selectedNozzleIds),
      operatorId: selectedOperatorId,
    }
    openMutation.mutate(req)
  }

  const selectedNozzles = selectedDU
    ? selectedDU.nozzles.filter((n) => selectedNozzleIds.has(n.id))
    : []

  const missingStep = !selectedDU ? 'Pick a dispensary unit'
    : selectedNozzleIds.size === 0 ? 'Select at least one nozzle'
    : !selectedOperatorId ? 'Pick an operator'
    : !readingConfirmed ? 'Confirm the meter readings'
    : null

  return (
    <Modal
      title="Open New Shift"
      subtitle="Select a Dispensary Unit and assign an operator"
      tone="info"
      size="md-wide"
      onClose={onClose}
      onSubmit={handleSubmit}
      bodyClassName="space-y-5"
      footerClassName="flex-wrap"
      footer={<>
            {selectedNozzles.length > 0 && (
              <label className="ui-check-row">
                <input
                  type="checkbox"
                  checked={readingConfirmed}
                  onChange={(e) => { setReadingConfirmed(e.target.checked); setValidationError(null) }}
                />
                <span>I confirm the meter readings above are correct and authorise opening this shift.</span>
              </label>
            )}
            <button type="button" onClick={onClose}
              className="ui-btn ui-btn-secondary flex-1">
              Cancel
            </button>
            {missingStep ? (
              <button type="submit" disabled
                className="ui-btn flex-1 whitespace-normal bg-surface-sunken text-ink border-control cursor-not-allowed disabled:opacity-100">
                {missingStep}
              </button>
            ) : (
              <button type="submit" disabled={openMutation.isPending}
                className="ui-btn ui-btn-primary flex-1 disabled:opacity-60 disabled:cursor-not-allowed">
                {openMutation.isPending ? 'Opening...' : 'Open Shift'}
              </button>
            )}
      </>}
    >

          {/* ── Step 1: Select DU ── */}
          <div>
            <span className="ui-label mb-2">
              Step 1 — Select Dispensary Unit
              {selectedDU && (
                <span className="ml-2 text-primary font-semibold normal-case">
                  DU #{selectedDU.duNumber} — {selectedDU.name}
                </span>
              )}
            </span>
            {dusLoading ? (
              <p className="ui-empty">Loading dispensary units...</p>
            ) : activeDUs.length === 0 ? (
              <div className="ui-alert ui-alert-warning text-xs">
                No active Dispensary Units found. Add one in Setup first.
              </div>
            ) : (
              <div className="grid grid-cols-3 gap-2">
                {activeDUs.map((du) => {
                  const isSelected = selectedDU?.id === du.id
                  const fuelTypes = du.nozzles
                    .filter((n) => n.status === 'ACTIVE')
                    .map((n) => FUEL_LABELS[n.fuelType] ?? n.fuelType)
                  return (
                    <button key={du.id} type="button"
                      onClick={() => handleSelectDU(du)}
                      className={`flex flex-col items-center gap-1.5 p-3 rounded-card border-2 transition-all ${
                        isSelected
                          ? 'border-primary bg-info-bg shadow-raised'
                          : 'border-border hover:border-primary hover:bg-bg'
                      }`}
                    >
                      <span className={`text-xs font-bold ${isSelected ? 'text-primary' : 'text-ink-3'}`}>
                        DU #{du.duNumber}
                      </span>
                      <span className={`text-sm font-bold ${isSelected ? 'text-primary' : 'text-ink'}`}>
                        {du.name}
                      </span>
                      <div className="flex flex-wrap gap-0.5 justify-center">
                        {fuelTypes.map((tag) => (
                          <span key={tag} className={`text-xs px-1.5 py-0.5 rounded-badge font-medium ${
                            isSelected ? 'bg-info-bg text-info' : 'bg-surface-sunken text-ink-3'
                          }`}>
                            {tag}
                          </span>
                        ))}
                      </div>
                      {isSelected && (
                        <span className="inline-flex items-center gap-1 text-xs text-primary font-semibold"><Check size={11} strokeWidth={2.5} />Selected</span>
                      )}
                    </button>
                  )
                })}
              </div>
            )}
          </div>

          {/* ── Step 2: Select Nozzles from selected DU ── */}
          {selectedDU && (
            <div>
              <span className="ui-label mb-2">
                Step 2 — Select Nozzle(s)
                {selectedNozzleIds.size > 0 && (
                  <span className="ml-2 text-primary font-semibold normal-case">
                    {selectedNozzleIds.size} selected
                  </span>
                )}
              </span>
              <div role="group" aria-label="Select nozzles" className="grid grid-cols-3 gap-2">
                {selectedDU.nozzles.filter((n) => n.status === 'ACTIVE').map((nozzle) => {
                  const isBusy     = busyNozzleIds.has(nozzle.id)
                  const isSelected = selectedNozzleIds.has(nozzle.id)
                  return (
                    <button key={nozzle.id} type="button"
                      disabled={isBusy}
                      onClick={() => handleNozzleToggle(nozzle)}
                      className={`flex flex-col items-center gap-1.5 p-3 rounded-card border-2 transition-all ${
                        isBusy
                          ? 'border-border bg-surface-sunken opacity-50 cursor-not-allowed'
                          : isSelected
                          ? 'border-primary bg-info-bg shadow-raised'
                          : 'border-border hover:border-primary hover:bg-bg'
                      }`}
                    >
                      <span className={`text-xl font-bold ${isSelected ? 'text-primary' : 'text-ink'}`}>
                        #{nozzle.nozzleNumber}
                      </span>
                      <span className={`text-xs px-1.5 py-0.5 rounded-badge font-medium ${
                        isSelected ? 'bg-info-bg text-info' : 'bg-surface-sunken text-ink-3'
                      }`}>
                        {FUEL_LABELS[nozzle.fuelType] ?? nozzle.fuelType}
                      </span>
                      {isBusy
                        ? <span className="text-xs text-ink-3">In use</span>
                        : isSelected && <span className="inline-flex items-center gap-1 text-xs text-primary font-semibold"><Check size={11} strokeWidth={2.5} />Selected</span>
                      }
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {/* ── Step 3: Operator ── */}
          <OperatorPicker
            title="Assign Operator"
            showStepNumber={!!selectedDU}
            operatorsLoading={operatorsLoading}
            noneAvailable={availableOperators.length === 0}
            filteredOperators={filteredOperators}
            operatorSearch={operatorSearch}
            onSearchChange={setOperatorSearch}
            selectedOperatorId={selectedOperatorId}
            onSelect={setSelectedOperatorId}
          />

          {/* ── Current meter readings (acknowledgment) ── */}
          {selectedNozzles.length > 0 && (
            <div className="ui-card-plain ui-card-muted p-4 space-y-3">
              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold text-ink-3 uppercase tracking-wide">
                  Current Meter Readings
                </span>
                <StatusBadge tone="info" icon={Lock} label="Read-only" />
              </div>
              <div className={`grid gap-2 ${selectedNozzles.length > 2 ? 'grid-cols-2' : 'grid-cols-1'}`}>
                {selectedNozzles.map((nozzle) => (
                  <div key={nozzle.id} className="ui-card-plain bg-surface border-border px-3 py-2.5">
                    <p className="text-xs text-ink-3 mb-0.5">
                      Nozzle #{nozzle.nozzleNumber} — {FUEL_LABELS[nozzle.fuelType] ?? nozzle.fuelType} ({FUEL_UNIT[nozzle.fuelType] ?? 'L'})
                    </p>
                    <p className="text-base font-semibold text-ink tabular-nums">
                      {nozzle.lastReading.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                    </p>
                  </div>
                ))}
              </div>
              <p className="text-xs text-ink-3 leading-relaxed">
                These readings are set automatically from the last shift close.
                If a reading looks incorrect, an Admin must update it in{' '}
                <span className="font-medium text-ink">Setup → Nozzle → Adjust Reading</span> first.
              </p>
            </div>
          )}

          {/* ── Errors ── */}
          {(validationError || serverError) && (
            <div role="alert" className="ui-alert ui-alert-danger">
              <p className="text-bad text-sm">{validationError ?? serverError}</p>
            </div>
          )}
    </Modal>
  )
}
