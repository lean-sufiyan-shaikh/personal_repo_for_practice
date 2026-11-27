import { useTranslation } from 'react-i18next'
import type { CreditSummary } from '../../../api/dayCloseApi'
import { MoneyText } from '../../../components/AmountText'
import { serverText } from '../../../utils/serverText'
import { FigureList, SummaryCard } from './SummaryCard'

export function CreditSection({ credit }: { credit: CreditSummary }) {
  const { t } = useTranslation('dayclose')
  return (
    <SummaryCard title={t('credit.title')} flag={credit.flag}>
      <FigureList label={t('credit.figures')} rows={[
        { label: t('credit.given'), value: <MoneyText value={credit.creditGiven} /> },
        { label: t('credit.collections'), value: <MoneyText value={credit.collections} /> },
        { label: t('credit.overdue', { count: credit.overdueClientCount }), value: <MoneyText value={credit.overdueAmount} tone={credit.overdueAmount > 0 ? 'warn' : undefined} /> },
        { label: t('credit.overLimit'), value: credit.overLimitClientCount },
      ]} />
      {credit.attentionItems.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-ink">{t('credit.alerts', { count: credit.openAttentionCount })}</h3>
          <ul className="mt-1 list-disc space-y-0.5 pl-5 text-sm text-ink-2">
            {credit.attentionItems.map((item) => (
              <li key={item.type}>{serverText(item.reasonCode, item.reasonParams, item.reason)}</li>
            ))}
          </ul>
        </div>
      )}
    </SummaryCard>
  )
}
