import { useTranslation } from 'react-i18next'
import { FormField } from '../../../components/FormField'
import { formatDecimal } from '../../../utils/format'
import type { BackfillForm } from './useBackfillForm'

/** Fills the pump's test measure on every chosen nozzle, and asks why when test litres go over the limit. */
export function BackfillTestLitresFooter({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const testVolume = form.pumpSettings?.testVolumeLitres
  return (
    <div className="space-y-2 p-3">
      <p className="text-xs text-ink-3">{t('testLitres.explanation')}</p>
      {testVolume !== undefined && testVolume > 0 && (
        <button type="button" className="ui-btn ui-btn-secondary ui-btn-compact"
          onClick={() => form.setTestLitres(Object.fromEntries([...form.selectedNozzleIds].map((id) => [id, String(testVolume)])))}>
          {t('testLitres.fillAll', { litres: formatDecimal(testVolume) })}
        </button>
      )}
      {form.testLitresNoteNeeded && (
        <FormField label={t('testLitres.noteLabel', { litres: formatDecimal(form.pumpSettings?.maxTestLitresPerNozzle ?? 0) })}>
          <textarea rows={2} maxLength={500} value={form.testLitresNote}
            onChange={(e) => form.setTestLitresNote(e.target.value)} />
        </FormField>
      )}
    </div>
  )
}
