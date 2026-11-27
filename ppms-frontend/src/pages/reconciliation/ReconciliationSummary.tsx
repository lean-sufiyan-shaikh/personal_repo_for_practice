import { useTranslation } from 'react-i18next'
import type { StatementImportSummary } from '../../api/bankReconciliationApi'
import { MoneyText } from '../../components/AmountText'
import { formatNumberIn } from '../../i18n/format'

/** How far one statement is reconciled: share of lines matched, the count per status and the money still unexplained. */
export function ReconciliationSummary({ statement }: { statement: StatementImportSummary }) {
  const { t } = useTranslation('reconciliation')
  const matchedPercent = statement.totalLines > 0 ? (statement.matchedLines / statement.totalLines) * 100 : 0
  const counts = [
    { key: 'matched', label: t('status.MATCHED'), value: statement.matchedLines },
    { key: 'suggested', label: t('status.SUGGESTED'), value: statement.suggestedLines },
    { key: 'unmatched', label: t('status.UNMATCHED'), value: statement.unmatchedLines },
    { key: 'ignored', label: t('status.IGNORED'), value: statement.ignoredLines },
  ]

  return (
    <div className="ui-summary-strip" role="group" aria-label={t('summary.label')}>
      <div className="ui-summary-strip__item">
        <span className="ui-summary-strip__label">{t('summary.matchedPercent')}</span>
        <span className="ui-summary-strip__value">{formatNumberIn(matchedPercent, { maximumFractionDigits: 0 })}%</span>
      </div>
      {counts.map((count) => (
        <div key={count.key} className="ui-summary-strip__item">
          <span className="ui-summary-strip__label">{count.label}</span>
          <span className="ui-summary-strip__value">{count.value}</span>
        </div>
      ))}
      <div className="ui-summary-strip__item">
        <span className="ui-summary-strip__label">{t('summary.unmatchedCredit')}</span>
        <MoneyText value={statement.unmatchedCreditAmount} className="ui-summary-strip__value" />
      </div>
      <div className="ui-summary-strip__item">
        <span className="ui-summary-strip__label">{t('summary.unmatchedDebit')}</span>
        <MoneyText value={statement.unmatchedDebitAmount} className="ui-summary-strip__value" />
      </div>
    </div>
  )
}
