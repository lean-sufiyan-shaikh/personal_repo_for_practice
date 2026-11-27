import { useTranslation } from 'react-i18next'
import { FormField } from '../../../components/FormField'
import { NumericInput } from '../../../components/NumericInput'
import { formatDecimal } from '../../../utils/format'

export interface TestLitresNozzle {
  nozzleId: number
  label: string
  /** Null until the closing reading is typed. */
  meterLitres: number | null
  invalid: boolean
}

/**
 * Litres each nozzle dispensed for its daily test and poured back into the tank. They are not charged and stay in
 * stock; above the pump's limit a note is needed.
 */
export function TestLitresFields({
  nozzles, values, onChange, testVolume, maxTestLitres, noteNeeded, note, onNoteChange,
}: {
  nozzles: TestLitresNozzle[]
  values: Record<number, string>
  onChange: (nozzleId: number, value: string) => void
  testVolume?: number
  maxTestLitres?: number
  noteNeeded: boolean
  note: string
  onNoteChange: (note: string) => void
}) {
  const { t } = useTranslation('shifts')
  const canFill = testVolume !== undefined && testVolume > 0
  return (
    <div className="mt-3 rounded-lg border border-border px-3 py-2">
      <h4 className="text-sm font-medium text-ink">{t('testLitres.title')}</h4>
      <p className="mt-1 text-xs text-ink-3">{t('testLitres.explanation')}</p>
      {canFill && (
        <button type="button" className="ui-btn ui-btn-secondary ui-btn-compact mt-2"
          onClick={() => nozzles.forEach((nozzle) => onChange(nozzle.nozzleId, String(testVolume)))}>
          {t('testLitres.fillAll', { litres: formatDecimal(testVolume) })}
        </button>
      )}
      <div className="mt-2 grid gap-3 sm:grid-cols-2">
        {nozzles.map((nozzle) => (
          <FormField key={nozzle.nozzleId} label={t('testLitres.nozzleLabel', { label: nozzle.label })}
            error={nozzle.invalid ? t('testLitres.aboveMeter', { litres: formatDecimal(nozzle.meterLitres ?? 0) }) : undefined}
            help={nozzle.meterLitres !== null && !nozzle.invalid
              ? t('testLitres.soldHelp', {
                  meter: formatDecimal(nozzle.meterLitres),
                  sold: formatDecimal(Math.max(0, nozzle.meterLitres - (Number(values[nozzle.nozzleId]) || 0))),
                })
              : undefined}>
            <NumericInput min="0" step="0.01" unit="L" placeholder="0" value={values[nozzle.nozzleId] ?? ''}
              onChange={(e) => onChange(nozzle.nozzleId, e.target.value)} />
          </FormField>
        ))}
      </div>
      {noteNeeded && (
        <FormField className="mt-3" label={t('testLitres.noteLabel', { litres: formatDecimal(maxTestLitres ?? 0) })}>
          <textarea rows={2} maxLength={500} value={note} onChange={(e) => onNoteChange(e.target.value)} />
        </FormField>
      )}
    </div>
  )
}
