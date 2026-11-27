import { useTranslation } from 'react-i18next'
import type { SalesSummary } from '../../../api/dayCloseApi'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { codeLabel } from '../../../constants/labels'
import { FigureList, SummaryCard } from './SummaryCard'

export function SalesSection({ sales }: { sales: SalesSummary }) {
  const { t } = useTranslation('dayclose')
  return (
    <SummaryCard title={t('sales.title')} flag={sales.flag}>
      {sales.fuels.length > 0 && (
        <ul aria-label={t('sales.byFuel')} className="divide-y divide-border rounded-control border border-border text-sm">
          {sales.fuels.map((fuel) => (
            <li key={fuel.fuelType} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 px-3 py-2">
              <span className="font-semibold text-ink">{codeLabel(fuel.fuelType)}</span>
              <span className="text-ink-2"><LitresText value={fuel.litres} /> · <MoneyText value={fuel.revenue} /></span>
              <span className="w-full text-xs text-ink-3 sm:w-auto">
                {t('sales.fuelMargin')} <MoneyText value={fuel.margin} tone={fuel.margin < 0 ? 'bad' : undefined} />
              </span>
            </li>
          ))}
        </ul>
      )}
      <FigureList label={t('sales.totals')} rows={[
        { label: t('sales.fuelLitres'), value: <LitresText value={sales.fuelLitres} /> },
        { label: t('sales.fuelRevenue'), value: <MoneyText value={sales.fuelRevenue} /> },
        { label: t('sales.fuelCost'), value: <MoneyText value={sales.fuelCost} /> },
        { label: t('sales.productRevenue'), value: <MoneyText value={sales.productRevenue} /> },
        { label: t('sales.productMargin'), value: <MoneyText value={sales.productMargin} /> },
        { label: t('sales.grossMargin'), value: <MoneyText value={sales.grossMargin} tone={sales.grossMargin < 0 ? 'bad' : 'ok'} /> },
      ]} />
    </SummaryCard>
  )
}
