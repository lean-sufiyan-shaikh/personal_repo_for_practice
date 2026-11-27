import { useTranslation } from 'react-i18next'
import type { OpenNozzleReading } from '../../../api/pumpApi'
import type { FuelType } from '../../../types/shift'
import { FormField } from '../../../components/FormField'
import { NumericInput } from '../../../components/NumericInput'
import { codeLabel } from '../../../constants/labels'

interface Props {
  nozzlesByFuel: Map<FuelType, OpenNozzleReading[]>
  valueOf: (nozzle: OpenNozzleReading) => string
  onChange: (nozzleId: number, value: string) => void
}

/** The meter reading of each open nozzle at the moment the price changes, so earlier litres keep the old price. */
export function OpenNozzleReadingsPanel({ nozzlesByFuel, valueOf, onChange }: Props) {
  const { t } = useTranslation('setup')
  const groups = [...nozzlesByFuel.entries()].filter(([, nozzles]) => nozzles.length > 0)
  if (groups.length === 0) return null

  return (
    <fieldset className="ui-card space-y-3 p-4">
      <legend className="text-sm font-semibold text-ink">{t('prices.openNozzles.heading')}</legend>
      <p className="ui-help">{t('prices.openNozzles.hint')}</p>
      {groups.map(([fuelType, nozzles]) => (
        <div key={fuelType} className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {nozzles.map((nozzle) => (
            <FormField key={nozzle.nozzleId}
              label={t('prices.openNozzles.nozzle', {
                fuel: codeLabel(fuelType), number: nozzle.nozzleNumber, du: nozzle.duName ?? '',
              })}>
              <NumericInput step="0.001" min="0" max={nozzle.maxMeterValue} value={valueOf(nozzle)}
                onChange={(event) => onChange(nozzle.nozzleId, event.target.value)} />
            </FormField>
          ))}
        </div>
      ))}
    </fieldset>
  )
}
