import { useTranslation } from 'react-i18next'
import type { CreditClient } from '../../../api/creditApi'
import { LiveFigures } from '../../../components/LiveFigures'
import { MoneyText } from '../../../components/AmountText'
import { toPaise } from '../../../utils/money'

interface CreditLimitFiguresProps {
  client: Pick<CreditClient, 'outstandingBalance' | 'creditLimit' | 'unlimited'>
  unlimited: boolean
  /** Null while the typed limit is not a number yet. */
  newLimit: number | null
}

/** The current and new limit beside the balance, and how much credit the new limit leaves. */
export function CreditLimitFigures({ client, unlimited, newLimit }: CreditLimitFiguresProps) {
  const { t } = useTranslation('credit')
  const noLimit = <span className="text-ink-2">{t('creditLimit.noLimit')}</span>
  const leftAfterPaise = unlimited || newLimit === null ? null : toPaise(newLimit) - toPaise(client.outstandingBalance)
  const isOverNewLimit = leftAfterPaise !== null && leftAfterPaise < 0

  return (
    <LiveFigures
      label={t('creditLimit.figuresLabel')}
      figures={[
        { label: t('creditLimit.outstanding'), value: <MoneyText value={client.outstandingBalance} /> },
        { label: t('creditLimit.currentLimit'), value: client.unlimited ? noLimit : <MoneyText value={client.creditLimit} /> },
        { label: t('creditLimit.newLimit'), value: unlimited ? noLimit : <MoneyText value={newLimit} /> },
        isOverNewLimit
          ? { label: t('creditLimit.overLimitBy'), value: <MoneyText value={Math.abs(leftAfterPaise) / 100} tone="bad" />, isResult: true }
          : { label: t('creditLimit.leftAfter'), value: unlimited ? noLimit : <MoneyText value={leftAfterPaise === null ? null : leftAfterPaise / 100} />, isResult: true },
      ]}
    />
  )
}
