import { useTranslation } from 'react-i18next'
import type { CashSummary } from '../../../api/dayCloseApi'
import { MoneyText } from '../../../components/AmountText'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency } from '../../../utils/format'
import { FigureList, SummaryCard } from './SummaryCard'

function SafeCountLine({ cash }: { cash: CashSummary }) {
  const { t } = useTranslation('dayclose')
  const count = cash.safeCount
  if (!count) return <p className="text-sm text-bad">{t('cash.noSafeCount')}</p>
  const result = count.varianceType === null
    ? t('cash.safeMatches')
    : `${codeLabel(count.varianceType)} ${formatCurrency(Math.abs(count.varianceAmount))}`
  return (
    <p className="text-sm text-ink-2">
      {t('cash.safeCount', { counted: formatCurrency(count.countedAmount), expected: formatCurrency(count.expectedAmount), result })}
    </p>
  )
}

export function CashSection({ cash }: { cash: CashSummary }) {
  const { t } = useTranslation('dayclose')
  return (
    <SummaryCard title={t('cash.title')} flag={cash.flag}>
      <FigureList label={t('cash.payments')} rows={[
        { label: t('cash.cash'), value: <MoneyText value={cash.cash} /> },
        { label: t('cash.upi'), value: <MoneyText value={cash.upi} /> },
        { label: t('cash.card'), value: <MoneyText value={cash.card} /> },
        { label: t('cash.fleetCard'), value: <MoneyText value={cash.fleetCard} /> },
        { label: t('cash.credit'), value: <MoneyText value={cash.credit} /> },
        { label: t('cash.shortages', { count: cash.shortShiftCount }), value: <MoneyText value={cash.shortageAmount} tone={cash.shortageAmount > 0 ? 'bad' : undefined} /> },
        { label: t('cash.overs', { count: cash.overShiftCount }), value: <MoneyText value={cash.overAmount} /> },
        { label: t('cash.drops', { count: cash.confirmedDropCount }), value: <MoneyText value={cash.confirmedDropAmount} /> },
        { label: t('cash.deposits', { count: cash.depositCount }), value: <MoneyText value={cash.depositAmount} /> },
      ]} />
      <SafeCountLine cash={cash} />
    </SummaryCard>
  )
}
