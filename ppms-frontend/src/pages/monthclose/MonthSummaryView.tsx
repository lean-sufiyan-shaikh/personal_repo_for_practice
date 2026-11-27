import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { MonthSummary } from '../../api/monthCloseApi'
import { MoneyText } from '../../components/AmountText'
import { codeLabel } from '../../constants/labels'
import { formatLitres } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import { FigureList } from '../dayclose/summary/SummaryCard'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="ui-card space-y-3 print:break-inside-avoid print:p-2">
      <h2 className="text-base font-semibold text-ink">{title}</h2>
      {children}
    </section>
  )
}

const money = (value: number, negative = false) => <MoneyText value={negative ? -value : value} showSign={negative} />

/** The stored month summary: sales, payments, costs and profit, credit, cash and stock, laid out to print on A4. */
export function MonthSummaryView({ summary }: { summary: MonthSummary }) {
  const { t } = useTranslation('dayclose')
  const { costs, payments, credit } = summary
  return (
    <div className="space-y-(--density-gap) print:space-y-2">
      <Section title={t('month.sales')}>
        <FigureList label={t('month.sales')} rows={[
          ...summary.fuels.map(fuel => ({
            label: `${codeLabel(fuel.fuelType)} · ${formatLitres(fuel.litres)}`,
            value: <>{money(fuel.revenue)} <span className="text-xs text-ink-3">({t('month.margin')} <MoneyText value={fuel.margin} />)</span></>,
          })),
          { label: t('month.fuelTotal', { litres: formatLitres(summary.fuelLitres) }), value: money(summary.fuelRevenue) },
          { label: t('month.products'), value: money(summary.productRevenue) },
          { label: t('month.grossProfit'), value: money(summary.grossProfit) },
        ]} />
      </Section>
      <Section title={t('month.payments')}>
        <FigureList label={t('month.payments')} rows={[
          { label: codeLabel('CASH'), value: money(payments.cash) },
          { label: codeLabel('UPI'), value: money(payments.upi) },
          { label: codeLabel('CARD'), value: money(payments.card) },
          { label: codeLabel('FLEET_CARD'), value: money(payments.fleetCard) },
          { label: codeLabel('CREDIT'), value: money(payments.credit) },
          { label: t('month.collectedTotal'), value: money(payments.total) },
        ]} />
      </Section>
      <Section title={t('month.costs')}>
        <FigureList label={t('month.costs')} rows={[
          { label: t('month.expenses'), value: money(costs.expenses, true) },
          { label: t('month.payroll'), value: money(costs.payroll, true) },
          { label: t('month.bankCharges'), value: money(costs.bankCharges, true) },
          { label: t('month.chargebacks'), value: money(costs.chargebacks, true) },
          ...(costs.badDebt !== 0 ? [{ label: t('month.badDebt'), value: money(costs.badDebt, true) }] : []),
          ...(costs.otherCosts !== 0 ? [{ label: t('month.otherCosts'), value: money(costs.otherCosts, true) }] : []),
          { label: t('month.netProfit'), value: <MoneyText value={summary.netProfit} tone={summary.netProfit < 0 ? 'bad' : 'ok'} className="font-semibold" /> },
        ]} />
      </Section>
      <Section title={t('month.creditAndCash')}>
        <FigureList label={t('month.creditAndCash')} rows={[
          { label: t('month.creditGiven'), value: money(credit.given) },
          { label: t('month.creditCollected'), value: money(credit.collected) },
          { label: t('month.creditOutstanding'), value: money(credit.outstandingAtClose) },
          { label: t('month.safeClosing'), value: money(summary.safeClosing) },
          { label: t('month.pettyCashClosing'), value: money(summary.pettyCashClosing) },
        ]} />
      </Section>
      <Section title={t('month.stock')}>
        <FigureList label={t('month.stock')} rows={summary.tanks.map(tank => ({
          label: `${tank.tankIdentifier} · ${codeLabel(tank.fuelType)}`,
          value: tank.closingLitres === null ? t('month.notDipped')
            : t('month.closingDip', { litres: formatLitres(tank.closingLitres), date: formatIstDate(tank.lastDipDate ?? '') }),
        }))} />
      </Section>
      {summary.exceptions.length > 0 && (
        <Section title={t('month.exceptions')}>
          <ul className="space-y-1 text-sm">
            {summary.exceptions.map(day => (
              <li key={day.date}><span className="font-medium">{formatIstDate(day.date)}</span> · {t(`month.dayStatus.${day.statusCode}`)} · {day.reason}</li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  )
}
