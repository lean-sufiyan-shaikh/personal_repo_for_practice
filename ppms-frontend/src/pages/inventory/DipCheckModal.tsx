import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../api/inventoryApi'
import { userApi } from '../../api/userApi'
import type { OpenShiftNozzle, TankStock } from '../../api/inventoryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { FUEL_COLORS } from './inventoryFormat'
import { NumericInput } from '../../components/NumericInput'
import { useToastStore } from '../../store/toastStore'
import { formatNumberIn } from '../../i18n/format'
import { codeLabel } from '../../constants/labels'

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
  const { t } = useTranslation(['inventory', 'common'])
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
      addToast(t('dipCheck.saved', { tank: tank.tankIdentifier }), 'success')
      onClose()
    },
    onError: (err) => {
      setError(parseApiError(err, t('dipCheck.failed')))
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
    if (!measured) { setError(t('dipCheck.measuredRequired')); return }
    if (openShiftNozzles.some(n => (meterReadings[n.nozzleId] ?? '') === '')) {
      setError(t('dipCheck.meterReadingsRequired'))
      return
    }
    mutation.mutate()
  }

  return (
    <Modal
      title={t('dipCheck.title')}
      subtitle={<>{tank.tankIdentifier} — <span className={cols.text}>{tank.fuelType}</span></>}
      tone="neutral"
      size="sm"
      onClose={onClose}
      closeOnBackdropClick
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={<>
        <button
          type="button"
          onClick={onClose}
          className="ui-btn ui-btn-secondary flex-1"
        >
          {t('common:actions.cancel')}
        </button>
        <button
          type="submit"
          disabled={mutation.isPending || openShiftNozzlesLoading}
          className="ui-btn ui-btn-primary flex-1"
        >
          {mutation.isPending ? t('dipCheck.saving') : t('dipCheck.save')}
        </button>
      </>}
    >
          {/* Current system stock */}
          <div className="bg-surface-sunken rounded-control px-4 py-3 text-xs text-ink-3">
            {t('dipCheck.systemStock')}{' '}
            <span className="font-bold text-ink tabular-nums">
              {formatNumberIn(tank.currentStock, { minimumFractionDigits: 1 })} L
            </span>
            <span className="block mt-1">{t('dipCheck.noBackdate')}</span>
          </div>

          {/* Meter readings of nozzles on open shifts */}
          {openShiftNozzlesLoading ? (
            <p className="text-xs text-ink-3">{t('dipCheck.checkingShifts')}</p>
          ) : openShiftNozzles.length > 0 && (
            <fieldset className="space-y-3">
              <legend className="ui-label">{t('dipCheck.meterReadings')}</legend>
              <p className="text-xs text-ink-3">
                {t('dipCheck.openShiftNote')}
              </p>
              {openShiftNozzles.map(n => (
                <div key={n.nozzleId}>
                  <label className="block">
                    <span className="ui-label">
                      {n.duName
                        ? t('dipCheck.nozzleReadingWithDu', { number: n.nozzleNumber, du: n.duName })
                        : t('dipCheck.nozzleReading', { number: n.nozzleNumber })}
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
                  <p id={`opening-reading-${n.nozzleId}`} className="text-xs text-ink-3 mt-1">
                    {t('dipCheck.openedAt', { reading: formatNumberIn(n.openingReading, { minimumFractionDigits: 3 }) })}
                  </p>
                </div>
              ))}
              {soldKnown && (
                <p className="text-xs text-ink-3">
                  {t('dipCheck.expectedStock')}{' '}
                  <span className="font-bold text-ink tabular-nums">
                    {formatNumberIn(expectedStock, { minimumFractionDigits: 1 })} L
                  </span>
                </p>
              )}
            </fieldset>
          )}

          {/* Measured quantity */}
          <div>
            <label className="block">
              <span className="ui-label">
              {t('dipCheck.dipstickReading')}
            </span>
              <NumericInput
                required
                step="0.001"
                min="0"
                value={measured}
                onChange={e => setMeasured(e.target.value)}
                placeholder={t('dipCheck.dipstickPlaceholder')}
                data-autofocus
              />
            </label>
          </div>

          {/* Live variance preview */}
          {variance !== null && (
            <div className={`rounded-control px-4 py-2.5 text-xs flex items-center justify-between ${
              Math.abs(variance) < tank.dipTolerance
                ? 'bg-ok-bg text-ok'
                : variance > 0
                  ? 'bg-warn-bg text-warn'
                  : 'bg-bad-bg text-bad'
            }`}>
              <span>
                {Math.abs(variance) < tank.dipTolerance
                  ? <span className="inline-flex items-center gap-1">{t('dipCheck.withinTolerance')}<Check size={12} strokeWidth={2.5} /></span>
                  : variance > 0
                    ? t('dipCheck.surplus')
                    : t('dipCheck.shortage')}
              </span>
              <span className="font-bold tabular-nums">
                {t('dipCheck.variance', { value: `${variance > 0 ? '+' : ''}${variance.toFixed(1)}` })}
              </span>
            </div>
          )}

          {/* Operator */}
          <div>
            <span className="ui-label">
              {t('dipCheck.checkedBy')} <span className="text-ink-3">{t('dipCheck.optional')}</span>
            </span>
            <SearchableSelect label={t('dipCheck.checkedBy')}
              value={checkedByUserId}
              onChange={v => setCheckedByUserId(v)}
              placeholder={t('dipCheck.selectOperator')}
              options={staff.map(s => ({
                value: String(s.id),
                label: t('dipCheck.staffOption', { name: s.fullName, role: codeLabel(s.role) }),
              }))}
            />
          </div>

          {/* Notes */}
          <div>
            <label className="block">
              <span className="ui-label">
              {t('dipCheck.notes')} <span className="text-ink-3">{t('dipCheck.optional')}</span>
            </span>
              <input
                type="text"
                value={notes}
                onChange={e => setNotes(e.target.value)}
                placeholder={t('dipCheck.notesPlaceholder')}
                className="text-sm"
              />
            </label>
          </div>

          {error && <p role="alert" className="ui-error-text">{error}</p>}
    </Modal>
  )
}
