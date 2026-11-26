import { useTranslation } from 'react-i18next'
import { MoneyText } from '../../../components/AmountText'
import { CashCountField } from '../../../components/cash/CashCountField'
import type { DenominationCounts } from '../../../components/cash/denominations'
import { LiveFigures } from '../../../components/LiveFigures'
import { toPaise } from '../../../utils/money'
import { emptyNum, fmt } from '../components/creditRow'
import { PaymentField, Section } from './CloseShiftFields'

interface Props {
  cash: string
  /** The bag counted note by note, or null when only the total is typed. */
  cashCounts: DenominationCounts | null
  upi: string
  card: string
  fleetCard: string
  onCashChange: (value: string) => void
  onCashCountsChange: (counts: DenominationCounts | null) => void
  onUpiChange: (value: string) => void
  onCardChange: (value: string) => void
  onFleetCardChange: (value: string) => void
  /** What the bag must hold for the shift to balance after drops, UPI, card, fleet card and credit. */
  restInCashPaise: number
  /** Confirmed drops already in the safe. */
  cashDropped: number
  creditTotal: number
}

export function PaymentSection({
  cash, cashCounts, upi, card, fleetCard, onCashChange, onCashCountsChange, onUpiChange, onCardChange, onFleetCardChange,
  restInCashPaise, cashDropped, creditTotal,
}: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const showFillRest = cashCounts === null && restInCashPaise > 0 && toPaise(emptyNum(cash)) !== restInCashPaise
  const fillRest = showFillRest ? (
    <button type="button" onClick={() => onCashChange((restInCashPaise / 100).toFixed(2))} className="ui-text-action">
      {t('close.payment.fillRest', { amount: fmt(restInCashPaise / 100) })}
    </button>
  ) : null

  return (
    <Section label="3" title={t('close.sections.payment')}>
      <div className="space-y-3">
        {cashDropped > 0 && (
          <LiveFigures label={t('close.drops.bagFigures')} figures={[
            { label: t('close.drops.droppedToSafe'), value: <MoneyText value={cashDropped} /> },
            { label: t('close.drops.expectedInBag'), value: <MoneyText value={restInCashPaise / 100} />, isResult: true },
          ]} />
        )}
        <CashCountField
          label={cashDropped > 0 ? t('close.payment.cashInBag') : t('close.payment.cash')}
          amount={cash} onAmountChange={onCashChange}
          counts={cashCounts} onCountsChange={onCashCountsChange}
          action={fillRest}
        />
        <div className="grid grid-cols-2 gap-x-3 gap-y-3 sm:grid-cols-3 sm:gap-x-4">
          <PaymentField label={t('close.payment.upi')} value={upi} onChange={onUpiChange} />
          <PaymentField label={t('close.payment.card')} value={card} onChange={onCardChange} />
          <PaymentField label={t('close.payment.fleetCard')} value={fleetCard} onChange={onFleetCardChange} />
        </div>
      </div>
      {creditTotal > 0 && (
        <p className="mt-3 flex items-center justify-between gap-3 text-sm">
          <span className="text-ink-2">{t('close.payment.creditTotal')}</span>
          <MoneyText value={creditTotal} className="font-semibold text-ink" />
        </p>
      )}
    </Section>
  )
}
