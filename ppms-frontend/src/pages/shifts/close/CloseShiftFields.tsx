import { useTranslation } from 'react-i18next'
import { NumericInput } from '../../../components/NumericInput'
import { MoneyText } from '../../../components/AmountText'
import { formatCurrency } from '../../../utils/format'
import { DiscrepancyBadge } from '../components/DiscrepancyBadge'
import { discrepancyTypeOf } from './closeShiftTotals'

export function Section({ label, title, optional = false, children }: {
  label: string; title: string; optional?: boolean; children: React.ReactNode
}) {
  const { t } = useTranslation(['shifts', 'common'])
  return (
    <div>
      <div className="flex items-center gap-2 mb-2.5">
        <span className="bg-primary text-on-primary text-xs font-bold rounded-full w-5 h-5 flex items-center justify-center shrink-0">
          {label}
        </span>
        <h3 className="text-sm font-semibold text-ink">{title}</h3>
        {optional && <span className="text-xs text-ink-3">{t('common:fields.optionalSuffix')}</span>}
      </div>
      {children}
    </div>
  )
}

export function ReadingField({ label, startValue, soldUnits, pricePerUnit, value, onChange, unitLabel, isInvalid, noChange, onNoChangeToggle, focusOnOpen = false }: {
  label: string; startValue: number; soldUnits: number; pricePerUnit: number;
  value: string; onChange: (v: string) => void; unitLabel: string; isInvalid?: boolean
  noChange?: boolean; onNoChangeToggle?: () => void; focusOnOpen?: boolean
}) {
  const { t } = useTranslation(['shifts', 'common'])
  const saleValue = soldUnits > 0 ? soldUnits * pricePerUnit : null

  return (
    <div>
      <span className="ui-label mb-0.5">{label}</span>
      <div className="flex flex-col gap-0.5 text-xs text-ink-3 mb-1">
        <span>{t('close.fields.start')} <span className="font-medium text-ink-3">{startValue}</span></span>
        {noChange ? (
          <span className="text-ink-3 italic">{t('close.fields.unchanged', { unit: unitLabel })}</span>
        ) : (
          <>
            {value !== '' && !isInvalid && soldUnits > 0 && (
              <span className="text-ink-3">
                <span className="font-medium">{value}</span>
                {' − '}
                <span className="font-medium">{startValue}</span>
                {' = '}
                <span className="font-semibold text-ok">{soldUnits.toFixed(3)} {unitLabel}</span>
              </span>
            )}
            {soldUnits > 0 && (
              <span className="text-info font-medium">
                {soldUnits.toFixed(2)} {unitLabel} × ₹{pricePerUnit}
                {saleValue != null && (
                  <span className="text-ok"> = {formatCurrency(saleValue)}</span>
                )}
              </span>
            )}
          </>
        )}
      </div>
      <NumericInput aria-label={t('close.fields.endReadingAria', { label })} step="0.001" min="0" required value={value}
        disabled={noChange}
        data-autofocus={focusOnOpen || undefined}
        unit={unitLabel}
        onChange={(e) => onChange(e.target.value)}
        className={`${
          noChange
            ? 'border-border bg-surface-sunken text-ink-3 cursor-not-allowed'
            : isInvalid
            ? 'border-bad bg-bad-bg focus:ring-bad'
            : 'border-control focus:ring-primary'

        }`}
        placeholder={noChange ? t('close.fields.sameAsOpening') : t('close.fields.enterEndReading')}
      />
      {isInvalid && (
        <p role="alert" className="ui-error-text mt-1">
          {t('close.fields.belowStart', { start: startValue })}
        </p>
      )}
      <label className="ui-check-row mt-2">
        <input type="checkbox" checked={noChange ?? false} onChange={onNoChangeToggle} />
        <span>{t('close.fields.noChange')}</span>
      </label>
    </div>
  )
}

export function PaymentField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  const { t } = useTranslation(['shifts', 'common'])
  return (
    <div>
      <label className="block">
        <span className="ui-label">{label}</span>
        <NumericInput step="0.01" min="0" value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={t('close.fields.enterAmount')}
        />
      </label>
    </div>
  )
}

export function BalanceCard({ totalDue, totalCollected, diff }: { totalDue: number; totalCollected: number; diff: number }) {
  const { t } = useTranslation(['shifts', 'common'])
  const borderClass = diff === 0 ? 'border-ok' : diff < 0 ? 'border-bad' : 'border-warn'
  return (
    <div className={`rounded-card border-2 overflow-hidden ${borderClass}`}>
      <div className="px-4 py-3 flex items-center justify-between bg-surface">
        <div className="text-xs text-ink-3 flex gap-6">
          <span>{t('close.fields.due')} <MoneyText value={totalDue} className="font-semibold text-ink" /></span>
          <span>{t('close.fields.collected')} <MoneyText value={totalCollected} className="font-semibold text-ink" /></span>
        </div>
        <DiscrepancyBadge type={discrepancyTypeOf(diff)} amount={Math.abs(diff)} />
      </div>
    </div>
  )
}
