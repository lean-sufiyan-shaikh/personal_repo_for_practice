import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { parseApiError } from '../../../utils/apiError'
import { localDateInputValue } from '../../../utils/date'
import { dipApi } from '../../../api/dipApi'
import type { TankInfo } from '../../../api/pumpApi'
import type { NozzleDetail } from '../../../types/shift'
import { Spinner } from '../../../components/Spinner'
import { useToastStore } from '../../../store/toastStore'
import { FUEL_LABELS } from '../../../constants/fuel'
import { FUEL_COLOR } from '../fuelStyles'
import { useInvalidateNozzles } from './nozzlePanels'

interface Props {
  pumpId: number
  nozzle: NozzleDetail
  /** The tank the nozzle draws from — drained fuel leaves this tank's stock. */
  tank: TankInfo | null
  onClose: () => void
}

export function RecordDipPanel({ pumpId, nozzle, tank, onClose }: Props) {
  const { addToast } = useToastStore()
  const queryClient = useQueryClient()
  const invalidate = useInvalidateNozzles(pumpId)
  const [dipLitres, setDipLitres] = useState('')
  const dipDate = localDateInputValue()
  const [dipReason, setDipReason] = useState('')
  const [dipError, setDipError] = useState<string | null>(null)

  const recordDipMutation = useMutation({
    mutationFn: ({ tankId, litresRemoved, reason, date }: { tankId: number; litresRemoved: number; reason: string; date: string }) =>
      dipApi.recordDip(pumpId, tankId, litresRemoved, reason, date),
    onSuccess: () => {
      onClose()
      addToast('Dip recorded', 'success')
      invalidate()
      queryClient.invalidateQueries({ queryKey: ['tanks', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pumpId] })
    },
    onError: (err) => { const msg = parseApiError(err, 'Failed to record dip'); setDipError(msg); addToast(msg, 'error') },
  })

  const submitDip = (e: React.FormEvent) => {
    e.preventDefault(); setDipError(null)
    if (!tank) { setDipError('Map this nozzle to a tank before recording drained fuel.'); return }
    if (!dipLitres || Number(dipLitres) <= 0) { setDipError('Enter a valid litres amount.'); return }
    if (Number(dipLitres) > tank.currentStock) { setDipError(`Tank ${tank.tankIdentifier} holds only ${tank.currentStock} L.`); return }
    if (!dipReason.trim()) { setDipError('Please provide a reason.'); return }
    recordDipMutation.mutate({
      tankId: tank.id,
      litresRemoved: Number(dipLitres),
      reason: dipReason,
      date: dipDate,
    })
  }

  return (
    <div className="px-4 py-3 border-t border-slate-100">
      <form onSubmit={submitDip} className="ui-dip-editor">
        <div className="ui-dip-editor__hero">
          <div>
            <p className="ui-section-kicker mb-2">Dip Record</p>
            <h3 className="ui-title-sm">Record fuel dip</h3>
            <p className="ui-subtitle mt-1">
              Record fuel physically removed from the tank, such as maintenance draining or contamination removal.
              The litres leave the tank&apos;s stock and the loss is valued at their purchase cost.
            </p>
          </div>
          <div className="ui-dip-editor__meta">
            <span className={`text-xs px-2 py-0.5 rounded-full font-medium border ${FUEL_COLOR[nozzle.fuelType] ?? ''}`}>
              {FUEL_LABELS[nozzle.fuelType]}
            </span>
          </div>
        </div>
        <p className="text-xs text-slate-500">
          {tank
            ? `From tank ${tank.tankIdentifier} · ${tank.currentStock.toLocaleString('en-IN', { minimumFractionDigits: 1 })} L in stock`
            : 'This nozzle is not mapped to a tank, so drained fuel cannot be recorded yet.'}
        </p>
        <div className="ui-dip-editor__section">
          <label className="block">
            <span className="ui-label">Litres Removed</span>
            <input
              type="number"
              step="0.001"
              min="0.001"
              value={dipLitres}
              onChange={(e) => setDipLitres(e.target.value)}
              placeholder="e.g. 12.500"
              className="text-xs min-h-10"
            />
          </label>
        </div>
        <div className="ui-dip-editor__grid">
          <div className="ui-dip-editor__section">
            <label className="block">
              <span className="ui-label">Date</span>
              <input
                type="date"
                value={dipDate}
                readOnly
                className="text-xs min-h-10 bg-slate-50"
              />
              <span className="block mt-1 text-xs text-slate-500">
                Recorded for today — drained fuel leaves today&apos;s stock, so it cannot be back-dated.
              </span>
            </label>
          </div>
          <div className="ui-dip-editor__section">
            <label className="block">
              <span className="ui-label">Reason <span className="text-red-500">*</span></span>
              <input
                type="text"
                value={dipReason}
                onChange={(e) => setDipReason(e.target.value)}
                placeholder="e.g. Water contamination draining"
                className="text-xs min-h-10"
              />
            </label>
          </div>
        </div>
        {dipError && <p role="alert" className="ui-error-text">{dipError}</p>}
        <div className="ui-dip-editor__actions">
          <button
            type="submit"
            disabled={recordDipMutation.isPending || !tank}
            className="ui-btn ui-btn-warning"
          >
            {recordDipMutation.isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-3 h-3" />Recording…</span> : 'Record Dip'}
          </button>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
            Cancel
          </button>
        </div>
      </form>    </div>
  )
}
