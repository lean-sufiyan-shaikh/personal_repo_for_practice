import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { ReconciliationPeriod, TankReconciliation } from '../../../api/inventoryApi'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { LitresText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { useIsPhone } from '../../../hooks/useIsPhone'
import { formatLitres, formatWithSign } from '../../../utils/format'
import { periodBadge, periodLabel, signedPercent } from './reconciliationFormat'

type Column = DataTableColumn<ReconciliationPeriod>

function litres(value: number | null): string {
  return value == null ? '—' : formatLitres(value)
}

function PeriodCell({ period }: { period: ReconciliationPeriod }) {
  const { t } = useTranslation('inventory')
  return (
    <span className="whitespace-nowrap">
      <span className="block font-medium text-ink">{periodLabel(period)}</span>
      {period.days > 1 && <span className="block text-xs text-ink-3">{t('reconciliation.days', { count: period.days })}</span>}
      {period.stockShortfallLitres != null && period.stockShortfallLitres > 0 && (
        <span className="block text-xs font-medium text-warn">
          {t('reconciliation.stockShortfall', { litres: formatLitres(period.stockShortfallLitres) })}
        </span>
      )}
    </span>
  )
}

function CheckCell({ period }: { period: ReconciliationPeriod }) {
  const { t } = useTranslation('inventory')
  return <StatusBadge {...periodBadge(t, period)} />
}

function desktopColumns(t: TFunction<'inventory'>): Column[] {
  const amount = (key: keyof ReconciliationPeriod, header: string, signed = false): Column => ({
    key, header, numeric: true,
    cell: (period) => <LitresText value={period[key] as number | null} showSign={signed} />,
  })
  return [
    { key: 'period', header: t('reconciliation.columns.period'), cell: (period) => <PeriodCell period={period} /> },
    amount('openingLitres', t('reconciliation.columns.opening')),
    amount('deliveredLitres', t('reconciliation.columns.delivered')),
    amount('soldLitres', t('reconciliation.columns.sold')),
    amount('removedLitres', t('reconciliation.columns.removed')),
    amount('expectedClosingLitres', t('reconciliation.columns.expected')),
    amount('closingLitres', t('reconciliation.columns.closing')),
    amount('varianceLitres', t('reconciliation.columns.variance'), true),
    { key: 'variancePercent', header: t('reconciliation.columns.variancePercent'), numeric: true, cell: (period) => signedPercent(t, period.variancePercent) },
    amount('cumulativeVarianceLitres', t('reconciliation.columns.cumulative'), true),
    { key: 'check', header: t('reconciliation.columns.check'), cell: (period) => <CheckCell period={period} /> },
  ]
}

function phoneColumns(t: TFunction<'inventory'>): Column[] {
  return [
    {
      key: 'period', header: t('reconciliation.columns.period'), cell: (period) => (
        <span className="flex items-start justify-between gap-3">
          <PeriodCell period={period} />
          <span className="text-right">
            <LitresText value={period.varianceLitres} showSign className="block font-semibold text-ink" />
            <span className="block text-xs text-ink-3 tabular-nums">{signedPercent(t, period.variancePercent)}</span>
          </span>
        </span>
      ),
    },
    {
      key: 'figures', header: t('reconciliation.columns.expected'), cell: (period) => period.status === 'RECONCILED' ? (
        <span className="block text-xs text-ink-3 tabular-nums">
          <span className="block">{t('reconciliation.phoneFlows', {
            opening: litres(period.openingLitres), delivered: litres(period.deliveredLitres),
            sold: litres(period.soldLitres), removed: litres(period.removedLitres),
          })}</span>
          <span className="block">{t('reconciliation.phoneClosing', { expected: litres(period.expectedClosingLitres), closing: litres(period.closingLitres) })}</span>
          <span className="block">{t('reconciliation.phoneCumulative', {
            litres: period.cumulativeVarianceLitres == null ? '—' : formatWithSign(period.cumulativeVarianceLitres, formatLitres),
            percent: signedPercent(t, period.cumulativeVariancePercent),
          })}</span>
        </span>
      ) : (
        <span className="block text-xs text-ink-3 tabular-nums">{t('reconciliation.phoneClosing', { expected: '—', closing: litres(period.closingLitres) })}</span>
      ),
    },
    { key: 'check', header: t('reconciliation.columns.check'), cell: (period) => <CheckCell period={period} /> },
  ]
}

/** The tank's periods, oldest first; flagged rows carry an icon and words, not colour alone. */
export function ReconciliationTable({ tank }: { tank: TankReconciliation }) {
  const { t } = useTranslation('inventory')
  const isPhone = useIsPhone()
  return (
    <DataTable
      caption={t('reconciliation.tableCaption', { tank: tank.tankIdentifier })}
      captionHidden
      columns={isPhone ? phoneColumns(t) : desktopColumns(t)}
      rows={tank.periods}
      rowKey={(period) => period.periodEnd}
      rowClassName={(period) => (period.withinTolerance === false ? 'bg-bad-bg' : undefined)}
      empty={<p className="ui-empty py-6 text-sm">{t('reconciliation.noPeriods')}</p>}
      cardsOnPhone
      className="rounded-none border-0"
    />
  )
}
