import { SearchableSelect } from '../../../components/SearchableSelect'
import { SectionLabel } from './SectionLabel'
import { maxDate, minDate } from './backfillHelpers'
import type { BackfillForm } from './useBackfillForm'

export function ShiftDetailsSection({ form }: { form: BackfillForm }) {
  const { shiftDate, setShiftDate, shiftDefinitionId, setShiftDefinitionId, selectedDU, setFuelRateInputs, defsLoading, definitions, dus, dusLoading, selectDU } = form
  return (
    <div className="rounded-card border border-border bg-surface-sunken p-4 space-y-3">
      <SectionLabel>Shift Details</SectionLabel>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block">
            <span className="ui-label">Shift Date <span className="text-bad">*</span></span>
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
          <p className="text-xs text-ink-3 mt-0.5">Last 365 days · not today</p>
        </div>

        <div>
          <span className="ui-label">Dispensary Unit <span className="text-bad">*</span></span>
          <SearchableSelect label="Dispensary Unit"
            value={String(selectedDU?.id ?? '')}
            onChange={(v) => selectDU(dus.find((d) => String(d.id) === v) ?? null)}
            options={dus.map((du) => ({ value: String(du.id), label: `${du.name} (DU #${du.duNumber})` }))}
            placeholder={dusLoading ? 'Loading…' : 'Select DU…'}
            disabled={dusLoading}
          />
        </div>
      </div>

      <div>
        <span className="ui-label">Shift Window <span className="text-bad">*</span></span>
        {definitions.length === 0 && !defsLoading ? (
          <div className="rounded-card border border-border bg-warn-bg px-3 py-2 text-sm text-warn">
            No shift definitions found for this pump. Configure shift windows in Setup first.
          </div>
        ) : (
          <SearchableSelect label="Shift Window"
            value={String(shiftDefinitionId ?? '')}
            onChange={(v) => setShiftDefinitionId(v ? Number(v) : null)}
            options={definitions.map((def) => ({ value: String(def.id), label: `${def.name} · ${def.windowLabel}` }))}
            placeholder={defsLoading ? 'Loading windows…' : 'Select shift window…'}
            disabled={defsLoading}
          />
        )}
      </div>
    </div>
  )
}
