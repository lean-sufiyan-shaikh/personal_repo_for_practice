import { useTranslation } from 'react-i18next'
import { Bar, BarChart, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis } from 'recharts'
import type { TankReconciliation } from '../../../api/inventoryApi'
import { LitresText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { codeLabel } from '../../../constants/labels'
import { CHART_COLORS, CHART_TOOLTIP_STYLE } from '../../../constants/chartColors'
import { formatLitres, formatWithSign } from '../../../utils/format'
import { periodLabel, signedPercent, summaryBadge } from './reconciliationFormat'

/** One tank over the selected dates: running variance, its share of litres sold, status and a bar per period. */
export function ReconciliationSummaryCard({ tank }: { tank: TankReconciliation }) {
  const { t } = useTranslation('inventory')
  const { summary } = tank
  const badge = summaryBadge(t, tank)
  const bars = tank.periods
    .filter(period => period.varianceLitres != null)
    .map(period => ({ label: periodLabel(period), variance: period.varianceLitres as number }))
  const tankLabel = t('tank.cardLabel', { tank: tank.tankIdentifier, fuel: codeLabel(tank.fuelType) })

  return (
    <article className="ui-card-plain space-y-2 p-4" aria-label={tankLabel}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink">{tankLabel}</h3>
        <StatusBadge tone={badge.tone} icon={badge.icon} label={badge.label} />
      </div>
      <div>
        <p className="text-xs text-ink-3">{t('reconciliation.cumulativeVariance')}</p>
        <p className="text-lg font-semibold text-ink">
          <LitresText value={summary.varianceLitres} showSign />
          <span className="ml-2 text-sm font-medium text-ink-2 tabular-nums">{signedPercent(t, summary.variancePercent)}</span>
        </p>
        <p className="text-xs text-ink-3">
          {t('reconciliation.soldInRange', { litres: formatLitres(summary.salesLitres) })}
          {summary.flaggedPeriodCount > 0 && <> · {t('reconciliation.flaggedCount', { count: summary.flaggedPeriodCount })}</>}
        </p>
      </div>
      {bars.length > 0 && (
        <div role="img" aria-label={t('reconciliation.trendLabel', { tank: tank.tankIdentifier })} className="h-14">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={bars} margin={{ top: 2, right: 0, left: 0, bottom: 2 }}>
              <XAxis dataKey="label" hide />
              <ReferenceLine y={0} stroke={CHART_COLORS.grid} />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                cursor={false}
                formatter={(value) => [formatWithSign(Number(value), formatLitres), t('reconciliation.columns.variance')]}
              />
              <Bar dataKey="variance" isAnimationActive={false}>
                {bars.map(bar => (
                  <Cell key={bar.label} fill={bar.variance < 0 ? CHART_COLORS.bad : CHART_COLORS.ok} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </article>
  )
}
