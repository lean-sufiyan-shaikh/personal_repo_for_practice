import { useState, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Lock } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { userApi } from '../../api/userApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import type { DUOption, NozzleDetail, OpenShiftRequest, Shift } from '../../types/shift'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { parseApiError } from '../../utils/apiError'
import { FUEL_UNIT } from '../../constants/fuel'
import { codeLabel } from '../../constants/labels'
import { formatDecimal } from '../../utils/format'
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
  const { t } = useTranslation(['shifts', 'common'])
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
      addToast(t('open.opened', { name: shift.operatorName }), 'success')
      onClose()
    },
    onError: (err) =>
      setServerError(parseApiError(err, t('open.failed'))),
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

    if (!selectedDU)                    { setValidationError(t('open.validation.selectDu')); return }
    if (selectedNozzleIds.size === 0)   { setValidationError(t('open.validation.selectNozzle')); return }
    if (!selectedOperatorId)            { setValidationError(t('open.validation.selectOperator')); return }
    if (!readingConfirmed)              { setValidationError(t('open.validation.confirmReadings')); return }

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

  const missingStep = !selectedDU ? t('open.missing.du')
    : selectedNozzleIds.size === 0 ? t('open.missing.nozzle')
    : !selectedOperatorId ? t('open.missing.operator')
    : !readingConfirmed ? t('open.missing.readings')
    : null

  return (
    <Modal
      title={t('open.title')}
      subtitle={t('open.subtitle')}
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
                <span>{t('open.confirmReadings')}</span>
              </label>
            )}
            <button type="button" onClick={onClose}
              className="ui-btn ui-btn-secondary flex-1">
              {t('common:actions.cancel')}
            </button>
            {missingStep ? (
              <button type="submit" disabled
                className="ui-btn flex-1 whitespace-normal bg-surface-sunken text-ink border-control cursor-not-allowed disabled:opacity-100">
                {missingStep}
              </button>
            ) : (
              <button type="submit" disabled={openMutation.isPending}
                className="ui-btn ui-btn-primary flex-1 disabled:opacity-60 disabled:cursor-not-allowed">
                {openMutation.isPending ? t('open.opening') : t('open.submit')}
              </button>
            )}
      </>}
    >

          {/* ── Step 1: Select DU ── */}
          <div>
            <span className="ui-label mb-2">
              {t('open.step1')}
              {selectedDU && (
                <span className="ml-2 text-primary font-semibold normal-case">
                  {t('open.selectedDu', { number: selectedDU.duNumber, name: selectedDU.name })}
                </span>
              )}
            </span>
            {dusLoading ? (
              <p className="ui-empty">{t('open.loadingDus')}</p>
            ) : activeDUs.length === 0 ? (
              <div className="ui-alert ui-alert-warning text-xs">
                {t('open.noDus')}
              </div>
            ) : (
              <div className="grid grid-cols-3 gap-2">
                {activeDUs.map((du) => {
                  const isSelected = selectedDU?.id === du.id
                  const fuelTypes = du.nozzles
                    .filter((n) => n.status === 'ACTIVE')
                    .map((n) => codeLabel(n.fuelType))
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
                        {t('open.duNumber', { number: du.duNumber })}
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
                        <span className="inline-flex items-center gap-1 text-xs text-primary font-semibold"><Check size={11} strokeWidth={2.5} />{t('open.selected')}</span>
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
                {t('open.step2')}
                {selectedNozzleIds.size > 0 && (
                  <span className="ml-2 text-primary font-semibold normal-case">
                    {t('open.selectedCount', { count: selectedNozzleIds.size })}
                  </span>
                )}
              </span>
              <div role="group" aria-label={t('open.selectNozzles')} className="grid grid-cols-3 gap-2">
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
                        {codeLabel(nozzle.fuelType)}
                      </span>
                      {isBusy
                        ? <span className="text-xs text-ink-3">{t('open.inUse')}</span>
                        : isSelected && <span className="inline-flex items-center gap-1 text-xs text-primary font-semibold"><Check size={11} strokeWidth={2.5} />{t('open.selected')}</span>
                      }
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {/* ── Step 3: Operator ── */}
          <OperatorPicker
            title={t('open.assignOperator')}
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
                  {t('open.currentReadings')}
                </span>
                <StatusBadge tone="info" icon={Lock} label={t('open.readOnly')} />
              </div>
              <div className={`grid gap-2 ${selectedNozzles.length > 2 ? 'grid-cols-2' : 'grid-cols-1'}`}>
                {selectedNozzles.map((nozzle) => (
                  <div key={nozzle.id} className="ui-card-plain bg-surface border-border px-3 py-2.5">
                    <p className="text-xs text-ink-3 mb-0.5">
                      {t('open.nozzleReadingLabel', { number: nozzle.nozzleNumber, fuel: codeLabel(nozzle.fuelType), unit: FUEL_UNIT[nozzle.fuelType] ?? 'L' })}
                    </p>
                    <p className="text-base font-semibold text-ink tabular-nums">
                      {formatDecimal(nozzle.lastReading)}
                    </p>
                  </div>
                ))}
              </div>
              <p className="text-xs text-ink-3 leading-relaxed">
                {t('open.readingsNote')}{' '}
                <span className="font-medium text-ink">{t('open.readingsNotePath')}</span>{t('open.readingsNoteEnd')}
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
