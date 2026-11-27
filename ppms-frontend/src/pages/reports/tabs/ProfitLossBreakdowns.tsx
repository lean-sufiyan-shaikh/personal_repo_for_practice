import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import type { ProfitLossReport } from '../../../api/reportApi'
import { MoneyText } from '../../../components/AmountText'

/** How the period's sales were paid, fleet card included, with the total collected. */
export function RevenueBreakdown({ report, className }: { report: ProfitLossReport; className: string }) {
  const { t } = useTranslation(['reports', 'common'])
  const figures = [
    { key: 'cash', label: t('common:paymentMethods.CASH'), value: report.totalCashRevenue, tone: undefined },
    { key: 'upi', label: t('common:paymentMethods.UPI'), value: report.totalUpiRevenue, tone: undefined },
    { key: 'card', label: t('common:paymentMethods.CARD'), value: report.totalCardRevenue, tone: undefined },
    { key: 'fleet', label: t('common:paymentMethods.FLEET_CARD'), value: report.totalFleetCardRevenue, tone: undefined },
    { key: 'credit', label: t('pl.creditSales'), value: report.totalCreditRevenue, tone: 'warn' as const },
  ]
  return (
    <div className={className}>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {figures.map((figure) => (
          <div key={figure.key}>
            <p className="ui-kpi-label">{figure.label}</p>
            <MoneyText value={figure.value} tone={figure.tone} className={`mt-0.5 block text-sm font-semibold ${figure.tone ? '' : 'text-ink'}`} />
          </div>
        ))}
      </div>
      <div className="mt-3 flex justify-between gap-3 border-t border-border pt-2 text-sm">
        <span className="font-medium text-ink">{t('pl.totalCollected')}</span>
        <MoneyText value={report.totalCollected} className="font-bold text-ink" />
      </div>
    </div>
  )
}

/** Gross profit less approved expenses and bank charges; payroll is not taken off yet, which the note says. */
export function OperatingProfitSection({ report, className = '' }: { report: ProfitLossReport; className?: string }) {
  const { t } = useTranslation('reports')
  const titleId = useId()
  const rows = [
    { key: 'gross', label: t('pl.grossProfit'), value: report.grossProfit, showSign: false },
    { key: 'expenses', label: t('pl.approvedExpenses'), value: -report.approvedExpenses, showSign: true },
    { key: 'charges', label: t('pl.bankCharges'), value: -report.bankCharges, showSign: true },
  ]
  return (
    <section aria-labelledby={titleId} className={`ui-card ui-card-muted p-3 ${className}`}>
      <h3 id={titleId} className="ui-section-heading">{t('pl.operatingTitle')}</h3>
      <dl className="mt-2 space-y-1 text-sm">
        {rows.map((row) => (
          <div key={row.key} className="flex justify-between gap-3">
            <dt className="text-ink-2">{row.label}</dt>
            <dd><MoneyText value={row.value} showSign={row.showSign} className="text-ink" /></dd>
          </div>
        ))}
        <div className="flex justify-between gap-3 border-t border-border pt-1">
          <dt className="font-semibold text-ink">{t('pl.netOperatingProfit')}</dt>
          <dd>
            <MoneyText value={report.netOperatingProfit} tone={report.netOperatingProfit >= 0 ? 'ok' : 'bad'} className="font-bold" />
          </dd>
        </div>
      </dl>
      <p className="mt-2 text-xs text-ink-3">{t('pl.payrollNotIncluded')}</p>
    </section>
  )
}
