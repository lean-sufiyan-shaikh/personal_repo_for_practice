import { roundToPaise } from '../../../utils/money'
import { useTranslation } from 'react-i18next'
import { codeLabel } from '../../../constants/labels'
import { SectionLabel } from './SectionLabel'
import { LitresText, MoneyText } from '../../../components/AmountText'
import type { BackfillForm } from './useBackfillForm'

export function MeterReadingsSection({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const { selectedNozzleIds, openingReadings, setOpeningReadings, closingReadings, setClosingReadings, availableNozzles, litersForNozzle, getEffectiveRate, nozzleExpected } = form
  return (
    <>
      {selectedNozzleIds.size > 0 && (
        <div className="rounded-card border border-border bg-surface-sunken p-4 space-y-3">
          <SectionLabel>{t('backfill.meter.title')}</SectionLabel>
          {[...selectedNozzleIds].map((nozzleId) => {
            const nozzle = availableNozzles.find((n) => n.id === nozzleId)
            if (!nozzle) return null
            const liters = litersForNozzle(nozzleId)
            const rate   = getEffectiveRate(nozzle.fuelType)
            const amount = nozzleExpected[nozzleId]
            return (
              <div key={nozzleId} className="bg-surface rounded-card border border-border p-3">
                <div className="flex items-center justify-between mb-2">
                  <p className="text-xs font-semibold text-ink-2">
                    {t('backfill.meter.nozzle', { number: nozzle.nozzleNumber, fuel: codeLabel(nozzle.fuelType) })}
                  </p>
                  {amount !== null && (
                    <MoneyText value={amount} className="text-xs font-bold text-info bg-info-bg rounded-badge px-2 py-0.5" />
                  )}
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block">
                      <span className="ui-label text-xs">{t('backfill.meter.opening')} <span className="text-bad">*</span></span>
                      <input
                        type="number" min="0" step="0.001" placeholder="0.000"
                        value={openingReadings[nozzleId] ?? ''}
                        onChange={(e) => setOpeningReadings((r) => ({ ...r, [nozzleId]: e.target.value }))}
                        className="ui-input"
                        required
                      />
                    </label>
                  </div>
                  <div>
                    <label className="block">
                      <span className="ui-label text-xs">{t('backfill.meter.closing')} <span className="text-bad">*</span></span>
                      <input
                        type="number" min="0" step="0.001" placeholder="0.000"
                        value={closingReadings[nozzleId] ?? ''}
                        onChange={(e) => setClosingReadings((r) => ({ ...r, [nozzleId]: e.target.value }))}
                        className="ui-input"
                        required
                      />
                    </label>
                  </div>
                </div>
                {liters !== null && liters >= 0 && rate !== null && (
                  <p className="mt-1.5 text-xs text-ink-3">
                    <LitresText value={liters} className="font-semibold text-ink" />
                    {' × '}
                    <span className="font-semibold text-ink">₹{rate.toFixed(2)}</span>
                    {' = '}
                    <MoneyText value={roundToPaise(liters * rate)} className="font-bold text-ok" />
                  </p>
                )}
                {liters !== null && liters >= 0 && rate === null && (
                  <p className="mt-1.5 text-xs text-warn">{t('backfill.meter.soldNoRate', { litres: liters.toFixed(3) })}</p>
                )}
                {liters !== null && liters < 0 && (
                  <p className="mt-1.5 text-xs text-bad">{t('backfill.meter.closingBelowOpening')}</p>
                )}
              </div>
            )
          })}
        </div>
      )}
    </>
  )
}
