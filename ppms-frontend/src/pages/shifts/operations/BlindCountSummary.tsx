import { useTranslation } from 'react-i18next'
import type { BlindCount } from '../../../types/shift'
import { MoneyText } from '../../../components/AmountText'
import { formatIstDateTime } from '../../../utils/date'

/** The locked blind count: what was counted, when and by whom. */
export function BlindCountSummary({ count }: { count: BlindCount }) {
  const { t } = useTranslation(['shifts', 'common'])
  const figures = [
    { label: t('close.payment.cashInBag'), value: count.cashCollected },
    { label: t('close.payment.upi'), value: count.upiCollected },
    { label: t('close.payment.card'), value: count.cardCollected },
    { label: t('close.payment.fleetCard'), value: count.fleetCardCollected },
  ]

  return (
    <section aria-label={t('blindCount.countedTitle')} className="space-y-2">
      <p className="text-xs text-ink-3">
        {t('blindCount.countedBy', {
          name: count.countedByName ?? '—',
          time: formatIstDateTime(count.countedAt, { hour: '2-digit', minute: '2-digit', hour12: true }),
        })}
      </p>
      <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {figures.map(({ label, value }) => (
          <div key={label} className="rounded-control bg-surface-sunken px-3 py-2">
            <dt className="text-xs text-ink-3">{label}</dt>
            <dd className="m-0 font-semibold text-ink"><MoneyText value={value} /></dd>
          </div>
        ))}
      </dl>
    </section>
  )
}
