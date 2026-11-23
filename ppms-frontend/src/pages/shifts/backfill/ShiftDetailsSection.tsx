import { useTranslation } from 'react-i18next'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { SectionLabel } from './SectionLabel'
import { maxDate, minDate } from './backfillHelpers'
import type { BackfillForm } from './useBackfillForm'

export function ShiftDetailsSection({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const { shiftDate, setShiftDate, shiftDefinitionId, setShiftDefinitionId, selectedDU, setFuelRateInputs, defsLoading, definitions, dus, dusLoading, selectDU } = form
  return (
    <div className="rounded-card border border-border bg-surface-sunken p-4 space-y-3">
      <SectionLabel>{t('backfill.details.title')}</SectionLabel>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block">
            <span className="ui-label">{t('backfill.details.shiftDate')} <span className="text-bad">*</span></span>
            <input
              type="date"
              value={shiftDate}
              min={minDate()}
              max={maxDate()}
              onChange={(e) => { setShiftDate(e.target.value); setShiftDefinitionId(null); setFuelRateInputs({}) }}
              className="ui-input"
              required
            />
          </label>
          <p className="text-xs text-ink-3 mt-0.5">{t('backfill.details.dateHint')}</p>
        </div>

        <div>
          <span className="ui-label">{t('backfill.details.du')} <span className="text-bad">*</span></span>
          <SearchableSelect label={t('backfill.details.du')}
            value={String(selectedDU?.id ?? '')}
            onChange={(v) => selectDU(dus.find((d) => String(d.id) === v) ?? null)}
            options={dus.map((du) => ({ value: String(du.id), label: t('backfill.details.duOption', { name: du.name, number: du.duNumber }) }))}
            placeholder={dusLoading ? t('backfill.details.loading') : t('backfill.details.selectDu')}
            disabled={dusLoading}
          />
        </div>
      </div>

      <div>
        <span className="ui-label">{t('backfill.details.window')} <span className="text-bad">*</span></span>
        {definitions.length === 0 && !defsLoading ? (
          <div className="rounded-card border border-border bg-warn-bg px-3 py-2 text-sm text-warn">
            {t('backfill.details.noDefinitions')}
          </div>
        ) : (
          <SearchableSelect label={t('backfill.details.window')}
            value={String(shiftDefinitionId ?? '')}
            onChange={(v) => setShiftDefinitionId(v ? Number(v) : null)}
            options={definitions.map((def) => ({ value: String(def.id), label: `${def.name} · ${def.windowLabel}` }))}
            placeholder={defsLoading ? t('backfill.details.loadingWindows') : t('backfill.details.selectWindow')}
            disabled={defsLoading}
          />
        )}
      </div>
    </div>
  )
}
