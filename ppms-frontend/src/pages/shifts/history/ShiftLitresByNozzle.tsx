import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { FUEL_UNIT } from '../../../constants/fuel'
import { codeLabel } from '../../../constants/labels'
import { formatUnits } from '../close/closeShiftTotals'

/** Each nozzle's meter litres, the test litres poured back into the tank and the litres sold. */
export function ShiftLitresByNozzle({ shift }: { shift: Shift }) {
  const { t } = useTranslation('shifts')
  const nozzleNumberById = new Map((shift.nozzles ?? []).map((nozzle) => [nozzle.id, nozzle.nozzleNumber]))
  const closedReadings = (shift.fuelReadings ?? []).filter((reading) => reading.unitsSold != null)
  if (closedReadings.length === 0) return null

  return (
    <section aria-labelledby={`shift-litres-${shift.id}`}>
      <h4 id={`shift-litres-${shift.id}`} className="mb-2 text-xs font-semibold text-ink-3">{t('testLitres.detail.title')}</h4>
      <ul className="divide-y divide-border rounded-card border border-border">
        {closedReadings.map((reading) => {
          const unit = FUEL_UNIT[reading.fuelType] ?? 'L'
          const sold = reading.unitsSold ?? 0
          const test = reading.testLitres ?? 0
          const meter = reading.meterLitres ?? sold + test
          return (
            <li key={reading.nozzleId} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 px-3 py-2">
              <span className="font-medium text-ink">
                {t('close.nozzleLine', { number: nozzleNumberById.get(reading.nozzleId) ?? '?', fuel: codeLabel(reading.fuelType) })}
              </span>
              <span className="tabular-nums text-ink-2">
                {t('testLitres.detail.line', {
                  meter: formatUnits(meter, unit), test: formatUnits(test, unit), sold: formatUnits(sold, unit),
                })}
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
