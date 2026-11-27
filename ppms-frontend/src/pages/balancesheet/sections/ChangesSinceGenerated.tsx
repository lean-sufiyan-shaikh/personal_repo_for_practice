import { useTranslation } from 'react-i18next'
import type { BalanceSheetDetail } from '../../../api/balanceSheetApi'
import { MoneyText } from '../../../components/AmountText'

/** How far each line total moved since the sheet was generated; net profit keeps the saved totals until it is regenerated. */
export function ChangesSinceGenerated({ detail }: { detail: BalanceSheetDetail }) {
  const { t } = useTranslation('balancesheet')
  const changes = [
    { label: t('detail.changes.dipNet'), amount: detail.dipNetChangeSinceGenerated },
    { label: t('detail.changes.productProfit'), amount: detail.productSalesProfitChangeSinceGenerated },
    { label: t('detail.changes.expenses'), amount: detail.expensesChangeSinceGenerated },
  ].filter((change) => change.amount != null && Number(change.amount) !== 0)

  if (changes.length === 0) return null
  return (
    <ul aria-label={t('detail.changes.title')} className="w-full space-y-0.5">
      {changes.map(({ label, amount }) => (
        <li key={label} className="flex flex-wrap items-center gap-2">
          <span>{label}</span>
          <MoneyText value={amount} showSign className="font-semibold" />
        </li>
      ))}
    </ul>
  )
}
