import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { NumericInput } from '../../../components/NumericInput'
import { codeLabel } from '../../../constants/labels'

interface Props {
  shift: Shift
  nozzleIds: number[]
  values: Record<number, string>
  onChange: (nozzleId: number, value: string) => void
}

/** A meter reading for each nozzle that changes hands. */
export function ReliefReadings({ shift, nozzleIds, values, onChange }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  if (nozzleIds.length === 0) return null
  return (
    <fieldset className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <legend className="ui-label">{t('operations.relief.readings')}</legend>
      {nozzleIds.map((nozzleId) => {
        const nozzle = shift.nozzles.find((n) => n.id === nozzleId)
        return (
          <label key={nozzleId} className="flex flex-col gap-1 text-sm">
            <span className="text-xs text-ink-3">
              {t('close.nozzleLine', { number: nozzle?.nozzleNumber ?? '?', fuel: nozzle ? codeLabel(nozzle.fuelType) : '' })}
            </span>
            <NumericInput step="0.001" min="0" value={values[nozzleId] ?? ''} onChange={(e) => onChange(nozzleId, e.target.value)} />
          </label>
        )
      })}
    </fieldset>
  )
}
