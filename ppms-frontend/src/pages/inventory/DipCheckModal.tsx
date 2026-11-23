import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { inventoryApi } from '../../api/inventoryApi'
import { userApi } from '../../api/userApi'
import type { OpenShiftNozzle, TankStock } from '../../api/inventoryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { ModalPortal } from '../../components/ModalPortal'
import { parseApiError } from '../../utils/apiError'
import { FUEL_COLORS } from './inventoryFormat'
import { IconButton } from '../../components/IconButton'
import { NumericInput } from '../../components/NumericInput'
import { useToastStore } from '../../store/toastStore'

const ROLLOVER_START_FRACTION = 0.99

/** Litres dispensed since the shift opened, following the server's meter rollover rule; null while unknown or invalid. */
function soldSinceOpening(nozzle: OpenShiftNozzle, reading: string): number | null {
  if (reading === '') return null
  const current = Number(reading)
  if (current >= nozzle.openingReading) return current - nozzle.openingReading
  if (nozzle.openingReading >= nozzle.maxMeterValue * ROLLOVER_START_FRACTION) {
    return nozzle.maxMeterValue - nozzle.openingReading + current
  }
  return null
}

// ── DIP Check Modal ────────────────────────────────────────────────────────────

export function DipCheckModal({
  pumpId,
  tank,
  onClose,
}: {
  pumpId: number
  tank: TankStock
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const cols = FUEL_COLORS[tank.fuelType] ?? FUEL_COLORS.PETROL

  const [measured,        setMeasured]        = useState('')
  const [meterReadings,   setMeterReadings]   = useState<Record<number, string>>({})
  const [checkedByUserId, setCheckedByUserId] = useState<string>('')
  const [notes,           setNotes]           = useState('')
  const [error,           setError]           = useState<string | null>(null)

  const { data: staff = [] } = useQuery({
    queryKey: ['staff', pumpId],
    queryFn:  () => userApi.getStaff(pumpId),
  })

  const { data: openShiftNozzles = [], isLoading: openShiftNozzlesLoading } = useQuery({
    queryKey: ['openShiftNozzles', pumpId, tank.tankId],
    queryFn:  () => inventoryApi.getOpenShiftNozzles(pumpId, tank.tankId),
  })

  const mutation = useMutation({
    mutationFn: () =>
      inventoryApi.recordDipCheck(pumpId, {
        tankId:           tank.tankId,
        measuredQuantity: Number(measured),
        checkedByUserId:  checkedByUserId ? Number(checkedByUserId) : undefined,
        notes:            notes.trim() || undefined,
        nozzleReadings:   openShiftNozzles.map(n => ({ nozzleId: n.nozzleId, meterReading: Number(meterReadings[n.nozzleId]) })),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['dipChecks', pumpId] })
      addToast(`DIP check saved for ${tank.tankIdentifier}`, 'success')
      onClose()
    },
    onError: (err) => {
      setError(parseApiError(err, 'Failed to record DIP check'))
      queryClient.invalidateQueries({ queryKey: ['openShiftNozzles', pumpId, tank.tankId] })
    },
  })

  const soldPerNozzle = openShiftNozzles.map(n => soldSinceOpening(n, meterReadings[n.nozzleId] ?? ''))
  const soldKnown = soldPerNozzle.every(sold => sold !== null)
  const soldOnOpenShifts = soldPerNozzle.reduce<number>((sum, sold) => sum + (sold ?? 0), 0)
  const expectedStock = tank.currentStock - soldOnOpenShifts
  const variance = measured !== '' && soldKnown ? Number(measured) - expectedStock : null

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!measured) { setError('Measured quantity is required.'); return }
    if (openShiftNozzles.some(n => (meterReadings[n.nozzleId] ?? '') === '')) {
      setError('Enter the current meter reading of every nozzle on an open shift.')
      return
    }
    mutation.mutate()
  }

  return (
    <ModalPortal onClose={onClose} closeOnBackdropClick>
    <div className="ui-modal-backdrop">
      <div className="ui-modal-panel w-full max-w-sm overflow-hidden">
        <div className="ui-modal-header ui-modal-header--themed ui-modal-header--neutral">
          <div className="ui-modal-heading">
            <h2 className="ui-modal-title">DIP Check</h2>
            <p className="ui-modal-subtitle">
              {tank.tankIdentifier} — <span className={cols.text.replace('text-', 'text-')}>{tank.fuelType}</span>
            </p>
          </div>
          <IconButton label="Close" icon="×" onClick={onClose} className="ui-btn ui-btn-ghost ui-modal-close" />
        </div>

        <form onSubmit={handleSubmit} className="ui-modal-body space-y-4">
          {/* Current system stock */}
          <div className="bg-slate-50 rounded-lg px-4 py-3 text-xs text-slate-500">
            System stock right now:{' '}
            <span className="font-bold text-slate-700">
              {tank.currentStock.toLocaleString('en-IN', { minimumFractionDigits: 1 })} L
            </span>
            <span className="block mt-1">Recorded for today — a DIP check cannot be back-dated.</span>
          </div>

          {/* Meter readings of nozzles on open shifts */}
          {openShiftNozzlesLoading ? (
            <p className="text-xs text-slate-500">Checking for open shifts on this tank…</p>
          ) : openShiftNozzles.length > 0 && (
            <fieldset className="space-y-3">
              <legend className="ui-label">Current meter readings</legend>
              <p className="text-xs text-slate-500">
                A shift is still open on this tank. Fuel it has sold since opening has not left stock yet,
                so enter each nozzle&apos;s meter reading now.
              </p>
              {openShiftNozzles.map(n => (
                <div key={n.nozzleId}>
                  <label className="block">
                    <span className="ui-label">
                      Nozzle #{n.nozzleNumber}{n.duName ? ` · ${n.duName}` : ''} meter reading
                    </span>
                    <NumericInput
                      required
                      step="0.001"
                      min="0"
                      value={meterReadings[n.nozzleId] ?? ''}
                      onChange={e => setMeterReadings(prev => ({ ...prev, [n.nozzleId]: e.target.value }))}
                      aria-describedby={`opening-reading-${n.nozzleId}`}
                    />
                  </label>
                  <p id={`opening-reading-${n.nozzleId}`} className="text-xs text-slate-500 mt-1">
                    Opened at {n.openingReading.toLocaleString('en-IN', { minimumFractionDigits: 3 })}
                  </p>
                </div>
              ))}
              {soldKnown && (
                <p className="text-xs text-slate-500">
                  Expected in tank after sales so far:{' '}
                  <span className="font-bold text-slate-700">
                    {expectedStock.toLocaleString('en-IN', { minimumFractionDigits: 1 })} L
                  </span>
                </p>
              )}
            </fieldset>
          )}

          {/* Measured quantity */}
          <div>
            <label className="block">
              <span className="ui-label">
              Dipstick Reading (Litres)
            </span>
              <NumericInput
                required
                step="0.001"
                min="0"
                value={measured}
                onChange={e => setMeasured(e.target.value)}
                placeholder="Physically measured quantity"
                data-autofocus
              />
            </label>
          </div>

          {/* Live variance preview */}
          {variance !== null && (
            <div className={`rounded-lg px-4 py-2.5 text-xs flex items-center justify-between ${
              Math.abs(variance) < tank.dipTolerance
                ? 'bg-emerald-50 text-emerald-700'
                : variance > 0
                  ? 'bg-amber-50 text-amber-700'
                  : 'bg-red-50 text-red-700'
            }`}>
              <span>
                {Math.abs(variance) < tank.dipTolerance
                  ? <span className="inline-flex items-center gap-1">Within tolerance<Check size={12} strokeWidth={2.5} /></span>
                  : variance > 0
                    ? 'Surplus detected'
                    : 'Shortage detected'}
              </span>
              <span className="font-bold">
                {variance > 0 ? '+' : ''}{variance.toFixed(1)} L variance
              </span>
            </div>
          )}

          {/* Operator */}
          <div>
            <span className="ui-label">
              Checked By <span className="text-slate-500">(optional)</span>
            </span>
            <SearchableSelect label="Checked By"
              value={checkedByUserId}
              onChange={v => setCheckedByUserId(v)}
              placeholder="— Select operator —"
              options={staff.map(s => ({
                value: String(s.id),
                label: `${s.fullName} (${s.role.charAt(0) + s.role.slice(1).toLowerCase()})`,
              }))}
            />
          </div>

          {/* Notes */}
          <div>
            <label className="block">
              <span className="ui-label">
              Notes <span className="text-slate-500">(optional)</span>
            </span>
              <input
                type="text"
                value={notes}
                onChange={e => setNotes(e.target.value)}
                placeholder="e.g. Reading taken at 6pm"
                className="text-sm"
              />
            </label>
          </div>

          {error && <p role="alert" className="ui-error-text">{error}</p>}

          <div className="ui-modal-footer -mx-6 -mb-6">
            <button
              type="button"
              onClick={onClose}
              className="ui-btn ui-btn-secondary flex-1"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={mutation.isPending || openShiftNozzlesLoading}
              className="ui-btn ui-btn-primary flex-1 bg-slate-700 hover:bg-slate-800 disabled:bg-slate-400"
            >
              {mutation.isPending ? 'Saving...' : 'Save DIP Check'}
            </button>
          </div>
        </form>
      </div>
    </div>
    </ModalPortal>
  )
}
