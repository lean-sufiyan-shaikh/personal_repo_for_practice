import { useTranslation } from 'react-i18next'
import type { SupplierLedger } from '../../api/supplierApi'
import { MoneyText } from '../../components/AmountText'
import { formatSupplierDate } from './supplierFormat'

const AGING_KEYS = ['days0To15', 'days16To30', 'days31To60', 'over60Days'] as const

/** Outstanding or advance, and how old the unpaid invoices are once payments have cleared the oldest first. */
export function SupplierBalanceSummary({ ledger }: { ledger: SupplierLedger }) {
  const { t } = useTranslation('suppliers')

  return (
    <section aria-label={t('detail.summary')} className="ui-card-plain mb-4 grid grid-cols-2 gap-4 p-4 md:grid-cols-6">
      <div>
        <p className="text-xs text-ink-3">{t('balances.outstanding')}</p>
        <MoneyText value={ledger.outstanding} className="text-lg font-semibold text-ink" />
      </div>
      <div>
        <p className="text-xs text-ink-3">{t('balances.advance')}</p>
        <MoneyText value={ledger.advance} tone={ledger.advance > 0 ? 'ok' : undefined} className="text-lg font-semibold" />
      </div>
      {AGING_KEYS.map(key => (
        <div key={key}>
          <p className="text-xs text-ink-3">{t(`aging.${key}`)}</p>
          <MoneyText value={ledger.aging[key]} className="font-medium text-ink" />
        </div>
      ))}
      <p className="col-span-2 text-xs text-ink-3 md:col-span-6">
        {t('detail.asOf', { date: formatSupplierDate(ledger.asOf) })}
        {ledger.openingBalance !== 0 && ` · ${t('detail.openingBalanceNote', { date: formatSupplierDate(ledger.openingBalanceDate) })}`}
      </p>
    </section>
  )
}
