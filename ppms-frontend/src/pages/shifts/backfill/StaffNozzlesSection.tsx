import { useTranslation } from 'react-i18next'
import { codeLabel } from '../../../constants/labels'
import { SectionLabel } from './SectionLabel'
import type { BackfillForm } from './useBackfillForm'

export function StaffNozzlesSection({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const { selectedDU, operatorId, setOperatorId, operatorSearch, setOperatorSearch, selectedNozzleIds, operatorsLoading, filteredOperators, availableNozzles, toggleNozzle, selectedOperator } = form
  return (
    <div className="rounded-card border border-border bg-surface-sunken p-4 space-y-3">
      <SectionLabel>{t('backfill.staff.title')}</SectionLabel>

      <div>
        <span className="ui-label">{t('backfill.staff.operator')} <span className="text-bad">*</span></span>
        {operatorsLoading ? (
          <div className="ui-input text-ink-3 text-sm">{t('backfill.staff.loadingOperators')}</div>
        ) : (
          <>
            <input aria-label={t('backfill.staff.operator')}
              type="text"
              placeholder={t('backfill.staff.searchPlaceholder')}
              value={operatorSearch}
              onChange={(e) => setOperatorSearch(e.target.value)}
              className="ui-input mb-1.5"
            />
            <div className="max-h-28 overflow-y-auto rounded-card border border-border divide-y divide-border bg-surface">
              {filteredOperators.length === 0 ? (
                <p className="text-xs text-ink-3 px-3 py-2">{t('backfill.staff.noOperators')}</p>
              ) : (
                filteredOperators.map((op) => (
                  <button
                    key={op.id}
                    type="button"
                    onClick={() => { setOperatorId(op.id); setOperatorSearch('') }}
                    className={`w-full text-left px-3 py-2 text-sm transition-colors ${
                      operatorId === op.id
                        ? 'bg-info-bg text-primary font-semibold'
                        : 'text-ink hover:bg-bg'
                    }`}
                  >
                    {op.fullName}
                    <span className="ml-2 text-xs text-ink-3 font-mono">{op.employeeId}</span>
                  </button>
                ))
              )}
            </div>
            {selectedOperator && (
              <p className="text-xs text-primary mt-1 font-medium">
                {t('backfill.staff.selected', { name: selectedOperator.fullName })}
              </p>
            )}
          </>
        )}
      </div>

      {selectedDU && (
        <div>
          <span className="ui-label">{t('backfill.staff.nozzles')} <span className="text-bad">*</span></span>
          {availableNozzles.length === 0 ? (
            <p className="text-xs text-ink-3">{t('backfill.staff.noNozzles')}</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {availableNozzles.map((nozzle) => {
                const selected = selectedNozzleIds.has(nozzle.id)
                return (
                  <button
                    key={nozzle.id}
                    type="button"
                    onClick={() => toggleNozzle(nozzle.id)}
                    className={`px-3 py-1.5 rounded-control border text-xs font-semibold transition-all ${
                      selected
                        ? 'bg-primary border-primary text-on-primary shadow-raised'
                        : 'border-control text-ink-2 bg-surface hover:border-primary hover:text-primary'
                    }`}
                  >
                    #{nozzle.nozzleNumber} · {codeLabel(nozzle.fuelType)}
                  </button>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
