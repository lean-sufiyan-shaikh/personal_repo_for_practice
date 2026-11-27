import { useTranslation } from 'react-i18next'
import type { DeliveryCheck, StockSummary, TankVariance } from '../../../api/dayCloseApi'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { codeLabel } from '../../../constants/labels'
import { formatDecimal } from '../../../utils/format'
import { FigureList, SummaryCard } from './SummaryCard'

const CHECK_TONE: Record<DeliveryCheck['checkStatus'], StatusTone> = { NOT_CHECKED: 'warn', OK: 'ok', SHORT: 'bad', OVER: 'warn' }

function TankRow({ tank }: { tank: TankVariance }) {
  const { t } = useTranslation('dayclose')
  const tone: StatusTone = !tank.dipped ? 'warn' : tank.withinTolerance === false ? 'bad' : 'ok'
  const label = !tank.dipped ? t('stock.notDipped') : tank.withinTolerance === false ? t('stock.outsideTolerance') : t('stock.withinTolerance')
  return (
    <li className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
      <span className="font-semibold text-ink">{tank.tankIdentifier} · {codeLabel(tank.fuelType)}</span>
      {tank.dipped && tank.varianceLitres !== null && (
        <span className="text-ink-2">
          <LitresText value={tank.varianceLitres} showSign />
          {tank.variancePercent !== null && ` (${formatDecimal(tank.variancePercent)}%)`}
        </span>
      )}
      <StatusBadge tone={tone} label={label} />
    </li>
  )
}

export function StockSection({ stock }: { stock: StockSummary }) {
  const { t } = useTranslation('dayclose')
  return (
    <SummaryCard title={t('stock.title')} flag={stock.flag}>
      {stock.tanks.length > 0 && (
        <ul aria-label={t('stock.tanks')} className="divide-y divide-border rounded-control border border-border">
          {stock.tanks.map((tank) => <TankRow key={tank.tankId} tank={tank} />)}
        </ul>
      )}
      {stock.deliveries.length === 0 ? (
        <p className="text-sm text-ink-3">{t('stock.noDeliveries')}</p>
      ) : (
        <ul aria-label={t('stock.deliveries')} className="divide-y divide-border rounded-control border border-border">
          {stock.deliveries.map((delivery) => (
            <li key={delivery.deliveryId} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
              <span className="text-ink">
                <span className="font-semibold">{delivery.invoiceReference}</span> · {codeLabel(delivery.fuelType)} · <LitresText value={delivery.invoicedLitres} />
              </span>
              <StatusBadge tone={CHECK_TONE[delivery.checkStatus]} label={codeLabel(delivery.checkStatus)} />
            </li>
          ))}
        </ul>
      )}
      <FigureList label={t('stock.claims')} rows={[
        { label: t('stock.openClaims', { count: stock.openClaimCount }), value: <MoneyText value={stock.openClaimAmount} /> },
      ]} />
    </SummaryCard>
  )
}
