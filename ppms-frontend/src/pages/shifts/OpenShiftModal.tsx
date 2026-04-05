import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { shiftApi } from '../../api/shiftApi'
import { userApi } from '../../api/userApi'
import type { NozzleOption, OpenShiftRequest, Shift } from '../../types/shift'

// Human-readable abbreviations for fuel types
const FUEL_ABBR: Record<string, string> = {
  PETROL: 'Petrol', SPEED_PETROL: 'Speed Petrol',
  DIESEL: 'Diesel', SPEED_DIESEL: 'Speed Diesel', CNG: 'CNG',
}

const FUEL_UNIT: Record<string, string> = {
  PETROL: 'L', SPEED_PETROL: 'L', DIESEL: 'L', SPEED_DIESEL: 'L', CNG: 'kg',
}

interface Props {
  pumpId: number
  activeShifts: Shift[]
  onClose: () => void
}

export default function OpenShiftModal({ pumpId, activeShifts, onClose }: Props) {
  const queryClient = useQueryClient()

  const [selectedNozzle, setSelectedNozzle]         = useState<NozzleOption | null>(null)
  const [selectedOperatorId, setSelectedOperatorId] = useState<number | null>(null)
  const [operatorSearch, setOperatorSearch]         = useState('')
  /** Operator must tick this before opening to confirm the displayed readings are correct. */
  const [readingConfirmed, setReadingConfirmed]     = useState(false)
  const [validationError, setValidationError]       = useState<string | null>(null)
  const [serverError, setServerError]               = useState<string | null>(null)

  const busyNozzleIds   = new Set(activeShifts.map((s) => s.nozzleId))
  const busyOperatorIds = new Set(activeShifts.map((s) => s.operatorId))

  const { data: nozzles = [], isLoading: nozzlesLoading } = useQuery({
    queryKey: ['nozzles', pumpId],
    queryFn:  () => shiftApi.getNozzles(pumpId),
  })

  const { data: operators = [], isLoading: operatorsLoading } = useQuery({
    queryKey: ['operators', pumpId],
    queryFn:  () => userApi.getOperators(pumpId),
  })

  const openMutation = useMutation({
    mutationFn: (req: OpenShiftRequest) => shiftApi.openShift(pumpId, req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['activeShifts', pumpId] })
      onClose()
    },
    onError: (err: any) =>
      setServerError(err?.response?.data?.message ?? 'Failed to open shift. Please try again.'),
  })

  const availableNozzles   = nozzles.filter((n) => !busyNozzleIds.has(n.id))
  const availableOperators = operators.filter((o) => !busyOperatorIds.has(o.id))
  const filteredOperators  = availableOperators.filter((o) =>
    o.fullName.toLowerCase().includes(operatorSearch.toLowerCase())
  )

  const handleNozzleSelect = (n: NozzleOption) => {
    setSelectedNozzle(n)
    setReadingConfirmed(false)
    setValidationError(null)
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setValidationError(null)
    setServerError(null)

    if (!selectedNozzle)      { setValidationError('Please select a nozzle'); return }
    if (!selectedOperatorId)  { setValidationError('Please select an operator'); return }
    if (!readingConfirmed)    { setValidationError('Please confirm the meter readings are correct'); return }

    // Start readings come from the outlet's stored lastReading on the backend — not sent from the client.
    const req: OpenShiftRequest = {
      nozzleId:   selectedNozzle.id,
      operatorId: selectedOperatorId,
    }
    openMutation.mutate(req)
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg overflow-hidden">

        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 bg-gradient-to-r from-blue-600 to-blue-700">
          <div>
            <h2 className="text-lg font-bold text-white">Open New Shift</h2>
            <p className="text-blue-200 text-xs mt-0.5">Select a nozzle and assign an operator</p>
          </div>
          <button type="button" onClick={onClose}
            className="text-blue-200 hover:text-white text-2xl font-light leading-none transition-colors">
            ×
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-5 max-h-[80vh] overflow-y-auto">

          {/* ── Nozzle cards ── */}
          <div>
            <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">
              Select Nozzle
            </label>
            {nozzlesLoading ? (
              <p className="text-sm text-slate-400">Loading nozzles...</p>
            ) : availableNozzles.length === 0 ? (
              <div className="bg-amber-50 border border-amber-200 rounded-xl p-3 text-xs text-amber-700">
                All nozzles are currently on active shifts.
              </div>
            ) : (
              <div className="grid grid-cols-3 gap-2">
                {availableNozzles.map((n) => {
                  const isSelected = selectedNozzle?.id === n.id
                  const fuelTags = n.outlets.map((o) => FUEL_ABBR[o.fuelType] ?? o.fuelType)
                  return (
                    <button key={n.id} type="button"
                      onClick={() => handleNozzleSelect(n)}
                      className={`flex flex-col items-center gap-1.5 p-3 rounded-xl border-2 transition-all ${
                        isSelected
                          ? 'border-blue-500 bg-blue-50 shadow-sm'
                          : 'border-slate-200 hover:border-blue-200 hover:bg-slate-50'
                      }`}
                    >
                      <span className={`text-xl font-bold ${isSelected ? 'text-blue-700' : 'text-slate-700'}`}>
                        #{n.nozzleNumber}
                      </span>
                      <div className="flex flex-wrap gap-0.5 justify-center">
                        {fuelTags.map((tag) => (
                          <span key={tag} className={`text-xs px-1.5 py-0.5 rounded-full font-medium ${
                            isSelected ? 'bg-blue-100 text-blue-700' : 'bg-slate-100 text-slate-500'
                          }`}>
                            {tag}
                          </span>
                        ))}
                      </div>
                    </button>
                  )
                })}
              </div>
            )}
          </div>

          {/* ── Operator searchable list ── */}
          <div>
            <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wide mb-2">
              Assign Operator
            </label>
            {operatorsLoading ? (
              <p className="text-sm text-slate-400">Loading operators...</p>
            ) : availableOperators.length === 0 ? (
              <div className="bg-amber-50 border border-amber-200 rounded-xl p-3 text-xs text-amber-700">
                No operators available — all are on active shifts or none are assigned to this pump.
              </div>
            ) : (
              <div className="border border-slate-200 rounded-xl overflow-hidden">
                <div className="flex items-center gap-2 px-3 py-2 bg-slate-50 border-b border-slate-200">
                  <svg className="w-4 h-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24"
                    stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round"
                      d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
                  </svg>
                  <input type="text" value={operatorSearch}
                    onChange={(e) => setOperatorSearch(e.target.value)}
                    placeholder="Search by name..."
                    className="flex-1 bg-transparent text-sm text-slate-700 focus:outline-none placeholder-slate-400" />
                  {operatorSearch && (
                    <button type="button" onClick={() => setOperatorSearch('')}
                      className="text-slate-400 hover:text-slate-600 text-lg leading-none">×</button>
                  )}
                </div>
                <div className="max-h-44 overflow-y-auto divide-y divide-slate-50">
                  {filteredOperators.length === 0 ? (
                    <p className="text-sm text-slate-400 p-3 text-center">No operators match "{operatorSearch}"</p>
                  ) : (
                    filteredOperators.map((o) => {
                      const isSelected = selectedOperatorId === o.id
                      return (
                        <button key={o.id} type="button"
                          onClick={() => setSelectedOperatorId(o.id)}
                          className={`w-full flex items-center gap-3 px-4 py-2.5 text-sm transition-colors text-left ${
                            isSelected ? 'bg-blue-50' : 'hover:bg-slate-50'
                          }`}
                        >
                          <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold shrink-0 ${
                            isSelected ? 'bg-blue-600 text-white' : 'bg-slate-200 text-slate-600'
                          }`}>
                            {o.fullName.charAt(0).toUpperCase()}
                          </div>
                          <span className={`flex-1 font-medium ${isSelected ? 'text-blue-800' : 'text-slate-700'}`}>
                            {o.fullName}
                          </span>
                          {isSelected && (
                            <svg className="w-4 h-4 text-blue-600 shrink-0" fill="currentColor" viewBox="0 0 20 20">
                              <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                            </svg>
                          )}
                        </button>
                      )
                    })
                  )}
                </div>
              </div>
            )}
          </div>

          {/* ── Current meter readings (read-only acknowledgment) ── */}
          {selectedNozzle && selectedNozzle.outlets.length > 0 && (
            <div className="rounded-xl border border-slate-200 bg-slate-50 p-4 space-y-3">
              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">
                  Current Meter Readings
                </span>
                <span className="text-xs bg-blue-100 text-blue-700 px-2 py-0.5 rounded-full font-medium">
                  Read-only
                </span>
              </div>
              <div className={`grid gap-2 ${selectedNozzle.outlets.length > 2 ? 'grid-cols-2' : 'grid-cols-1'}`}>
                {selectedNozzle.outlets.map((outlet) => (
                  <div key={outlet.outletId} className="bg-white rounded-lg border border-slate-200 px-3 py-2.5">
                    <p className="text-xs text-slate-500 mb-0.5">
                      {FUEL_ABBR[outlet.fuelType] ?? outlet.fuelType} ({FUEL_UNIT[outlet.fuelType] ?? 'L'})
                    </p>
                    <p className="text-base font-semibold text-slate-800">
                      {outlet.lastReading.toLocaleString('en-IN', { minimumFractionDigits: 3 })}
                    </p>
                  </div>
                ))}
              </div>
              <p className="text-xs text-slate-500 leading-relaxed">
                These readings are set automatically from the last shift close.
                If a reading looks incorrect, an Admin must update it in{' '}
                <span className="font-medium text-slate-700">Setup → Nozzle → Adjust Reading</span> first.
              </p>

              {/* Confirmation checkbox */}
              <label className="flex items-start gap-3 cursor-pointer group">
                <input
                  type="checkbox"
                  checked={readingConfirmed}
                  onChange={(e) => { setReadingConfirmed(e.target.checked); setValidationError(null) }}
                  className="mt-0.5 w-4 h-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
                />
                <span className="text-xs text-slate-600 leading-relaxed group-hover:text-slate-800 transition-colors">
                  I confirm the meter readings above are correct and authorise opening this shift.
                </span>
              </label>
            </div>
          )}

          {/* ── Errors ── */}
          {(validationError || serverError) && (
            <div className="bg-red-50 border border-red-200 rounded-xl p-3">
              <p className="text-red-600 text-sm">{validationError ?? serverError}</p>
            </div>
          )}

          {/* ── Actions ── */}
          <div className="flex gap-3 pt-1">
            <button type="button" onClick={onClose}
              className="flex-1 border border-slate-300 text-slate-700 py-2.5 rounded-xl text-sm font-medium hover:bg-slate-50 transition-colors">
              Cancel
            </button>
            <button
              type="submit"
              disabled={!selectedNozzle || !selectedOperatorId || !readingConfirmed || openMutation.isPending}
              className="flex-1 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 disabled:cursor-not-allowed text-white py-2.5 rounded-xl text-sm font-semibold transition-colors">
              {openMutation.isPending ? 'Opening...' : 'Open Shift'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
