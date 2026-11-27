import { useTranslation } from 'react-i18next'
import type { OperatorShare } from '../../../types/shift'
import { LitresText, MoneyText } from '../../../components/AmountText'

/** Each operator's litres and amount due at close, what they handed in when split, and the combined row. */
export function OperatorSharesTable({ shares }: { shares: OperatorShare[] }) {
  const { t } = useTranslation(['shifts', 'common'])
  if (shares.length === 0) return null
  const sum = (pick: (share: OperatorShare) => number | null) => shares.reduce((total, share) => total + (pick(share) ?? 0), 0)
  const split = shares.every((share) => share.collected != null)

  return (
    <section className="space-y-2" aria-label={t('operations.shares.title')}>
      <h3 className="ui-title-sm">{t('operations.shares.title')}</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-ink-3">
              <th className="py-1 pr-3 font-medium">{t('operations.shares.operator')}</th>
              <th className="py-1 pr-3 text-right font-medium">{t('operations.shares.litres')}</th>
              <th className="py-1 pr-3 text-right font-medium">{t('operations.shares.due')}</th>
              <th className="py-1 pr-3 text-right font-medium">{t('operations.shares.collected')}</th>
              <th className="py-1 text-right font-medium">{t('operations.shares.difference')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {shares.map((share) => (
              <tr key={share.operatorId}>
                <td className="py-1.5 pr-3 text-ink">{share.operatorName ?? '—'}</td>
                <td className="py-1.5 pr-3 text-right"><LitresText value={share.litresSold} /></td>
                <td className="py-1.5 pr-3 text-right"><MoneyText value={share.amountDue} /></td>
                <td className="py-1.5 pr-3 text-right"><MoneyText value={share.collected} /></td>
                <td className="py-1.5 text-right"><MoneyText value={share.difference} showSign /></td>
              </tr>
            ))}
            {shares.length > 1 && (
              <tr className="font-semibold">
                <td className="py-1.5 pr-3 text-ink">{t('operations.shares.combined')}</td>
                <td className="py-1.5 pr-3 text-right"><LitresText value={sum((share) => share.litresSold)} /></td>
                <td className="py-1.5 pr-3 text-right"><MoneyText value={sum((share) => share.amountDue)} /></td>
                <td className="py-1.5 pr-3 text-right"><MoneyText value={split ? sum((share) => share.collected) : null} /></td>
                <td className="py-1.5 text-right"><MoneyText value={split ? sum((share) => share.difference) : null} showSign /></td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {!split && shares.length > 1 && <p className="text-xs text-ink-3">{t('operations.shares.notSplit')}</p>}
    </section>
  )
}
