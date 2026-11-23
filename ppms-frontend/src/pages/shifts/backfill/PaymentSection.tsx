import { Check } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SectionLabel } from './SectionLabel'
import { MoneyText } from '../../../components/AmountText'
import type { BackfillForm } from './useBackfillForm'

export function PaymentSection({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const { selectedNozzleIds, cashCollected, setCashCollected, upiCollected, setUpiCollected, cardCollected, setCardCollected, fleetCardCollected, setFleetCardCollected, allRatesResolved, totalExpected, totalCollected, discrepancyAmt } = form
  return (
    <div className="rounded-card border border-border bg-surface-sunken p-4">
      <SectionLabel>{t('backfill.payment.title')}</SectionLabel>
      <div className="grid grid-cols-2 gap-3">
        {[
          { label: t('backfill.payment.upi'), value: upiCollected,       set: setUpiCollected },
          { label: t('backfill.payment.card'), value: cardCollected,      set: setCardCollected },
          { label: t('backfill.payment.fleetCard'), value: fleetCardCollected, set: setFleetCardCollected },
          { label: t('backfill.payment.cash'), value: cashCollected,      set: setCashCollected },
        ].map(({ label, value, set }) => (
          <div key={label}>
            <label className="block">
              <span className="ui-label text-xs">{label}</span>
              <input
                type="number" min="0" step="0.01" placeholder="0.00"
                value={value}
                onChange={(e) => set(e.target.value)}
                className="ui-input"
              />
            </label>
          </div>
        ))}
      </div>

      {/* Expected vs Collected summary */}
      {totalExpected !== null && selectedNozzleIds.size > 0 && allRatesResolved && (
        <div className="mt-3 rounded-card border border-border bg-surface divide-y divide-border">
          <div className="flex items-center justify-between px-3 py-2 text-xs text-ink-2">
            <span>{t('backfill.payment.expected')}</span>
            <MoneyText value={totalExpected} className="font-semibold text-ink" />
          </div>
          <div className="flex items-center justify-between px-3 py-2 text-xs text-ink-2">
            <span>{t('backfill.payment.collected')}</span>
            <MoneyText value={totalCollected} className="font-semibold text-ink" />
          </div>
          {discrepancyAmt !== null && (
            <div className={`flex items-center justify-between px-3 py-2 text-xs font-semibold ${
              Math.abs(discrepancyAmt) < 0.01
                ? 'text-ok bg-ok-bg'
                : discrepancyAmt < 0
                ? 'text-bad bg-bad-bg'
                : 'text-warn bg-warn-bg'
            }`}>
              <span>
                {Math.abs(discrepancyAmt) < 0.01 ? <span className="inline-flex items-center gap-1">{t('backfill.payment.balanced')}<Check size={12} strokeWidth={2.5} aria-hidden="true" /></span> : discrepancyAmt < 0 ? t('backfill.payment.short') : t('backfill.payment.over')}
              </span>
              <MoneyText value={Math.abs(discrepancyAmt) < 0.01 ? 0 : Math.abs(discrepancyAmt)} />
            </div>
          )}
        </div>
      )}
    </div>
  )
}
