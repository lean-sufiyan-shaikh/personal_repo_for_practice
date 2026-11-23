import { FUEL_LABELS } from '../../../constants/fuel'
import { SectionLabel } from './SectionLabel'
import type { BackfillForm } from './useBackfillForm'

export function FuelRatesSection({ form }: { form: BackfillForm }) {
  const { shiftDate, selectedNozzleIds, fuelRateInputs, setFuelRateInputs, dbPriceMap, selectedFuelTypes } = form
  return (
    <>
      {selectedNozzleIds.size > 0 && (
        <div className="rounded-card border border-border bg-info-bg p-4 space-y-2.5">
          <SectionLabel>Fuel Rates on {shiftDate}</SectionLabel>

          {[...selectedFuelTypes].map((fuelType) => {
            const dbPrice  = dbPriceMap[fuelType]
            const hasPrice = typeof dbPrice === 'number'

            return (
              <div key={fuelType} className="flex items-center gap-3 rounded-card px-2.5 py-1.5">
                <span className="text-xs font-semibold text-ink w-28 shrink-0">
                  {FUEL_LABELS[fuelType] ?? fuelType}
                </span>

                {hasPrice ? (
                  /* ── DB price, read-only display ── */
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-ok bg-ok-bg rounded-badge px-2.5 py-1 tabular-nums">
                      ₹{dbPrice!.toFixed(2)}/L
                    </span>
                    <span className="text-xs text-ink-3">
                      from price history · to change it, correct the price history first
                    </span>
                  </div>
                ) : (
                  /* ── Manual input (no DB price) ── */
                  <div className="flex items-center gap-2 flex-1">
                    <div className="relative flex-1 max-w-[140px]">
                      <span className="absolute left-2.5 top-1/2 -translate-y-1/2 text-xs text-ink-3 pointer-events-none">₹</span>
                      <input aria-label={`${FUEL_LABELS[fuelType] ?? fuelType} rate per litre`}
                        type="number"
                        min="0.01"
                        step="0.01"
                        placeholder="0.00"
                        value={fuelRateInputs[fuelType] ?? ''}
                        onChange={(e) => setFuelRateInputs(r => ({ ...r, [fuelType]: e.target.value }))}
                        className="ui-input pl-6 text-sm"
                      />
                    </div>
                    <span className="text-xs text-ink-3">/L</span>
                    <span className="text-xs text-warn font-medium">
                      No price on record — enter manually
                    </span>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </>
  )
}
