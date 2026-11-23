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
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'

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

export function DipCheckModal({
  pumpId,
  tank,
  tanks = [],
  onTankChange,
  onClose,
}: {
  pumpId: number
  tank: TankStock
  /** With onTankChange, shows a tank picker when the pump has more than one tank. */
  tanks?: TankStock[]
  onTankChange?: (tankId: number) => void
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
      subtitle={<>{tank.tankIdentifier} — <span className={cols.text}>{codeLabel(tank.fuelType)}</span></>}
      tone="neutral"
      size="md"
      onClose={onClose}
      closeOnBackdropClick
      onSubmit={handleSubmit}
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
      <FormGrid>
          {onTankChange && tanks.length > 1 && (
            <FormGridFullRow>
              <span className="ui-label">{t('dipCheck.tank')}</span>
              <SearchableSelect label={t('dipCheck.tank')}
                value={String(tank.tankId)}
                onChange={v => onTankChange(Number(v))}
                options={tanks.map(option => ({ value: String(option.tankId), label: `${option.tankIdentifier} · ${codeLabel(option.fuelType)}` }))}
              />
            </FormGridFullRow>
          )}

          <FormGridFullRow className="bg-surface-sunken rounded-control px-4 py-3 text-xs text-ink-3">
            {t('dipCheck.systemStock')}{' '}
            <span className="font-bold text-ink tabular-nums">
              {formatNumberIn(tank.currentStock, { minimumFractionDigits: 1 })} L
            </span>
            <span className="block mt-1">{t('dipCheck.noBackdate')}</span>
          </FormGridFullRow>

          {openShiftNozzlesLoading ? (
            <FormGridFullRow><p className="text-xs text-ink-3">{t('dipCheck.checkingShifts')}</p></FormGridFullRow>
          ) : openShiftNozzles.length > 0 && (
            <fieldset className="ui-form-grid__full space-y-3">
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

          <div>
            <span className="ui-label">
              {t('dipCheck.checkedBy')} <span className="text-ink-3">{t('common:fields.optionalSuffix')}</span>
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

          {variance !== null && (
            <div className={`ui-form-grid__full rounded-control px-4 py-2.5 text-xs flex items-center justify-between ${
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

          <FormGridFullRow>
            <label className="block">
              <span className="ui-label">
              {t('common:fields.notes')} <span className="text-ink-3">{t('common:fields.optionalSuffix')}</span>
            </span>
              <input
                type="text"
                value={notes}
                onChange={e => setNotes(e.target.value)}
                placeholder={t('dipCheck.notesPlaceholder')}
                className="text-sm"
              />
            </label>
          </FormGridFullRow>

          {error && <FormGridFullRow><p role="alert" className="ui-error-text">{error}</p></FormGridFullRow>}
      </FormGrid>
    </Modal>
  )
}
