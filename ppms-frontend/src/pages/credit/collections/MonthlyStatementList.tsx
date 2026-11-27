import { useTranslation } from 'react-i18next'
import { Printer } from 'lucide-react'
import { collectionApi } from '../../../api/collectionApi'
import type { MonthlyStatement } from '../../../api/collectionApi'
import { MoneyText } from '../../../components/AmountText'
import { formatIstDate } from '../../../utils/date'

interface MonthlyStatementListProps {
  pumpId: number
  statements: MonthlyStatement[]
  /** The pump-wide list names each client; a client's own list shows the month instead. */
  showClient: boolean
}

/** Stored monthly statements, each opening the page exactly as it was generated, to print or save as PDF. */
export function MonthlyStatementList({ pumpId, statements, showClient }: MonthlyStatementListProps) {
  const { t } = useTranslation('collections')
  return (
    <ul className="divide-y divide-border">
      {statements.map((statement) => (
        <li key={statement.id} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3">
          <div className="min-w-0">
            <p className="break-words font-medium text-ink">
              {showClient ? statement.clientName
                : t('statements.period', { from: formatIstDate(statement.periodFrom), to: formatIstDate(statement.periodTo) })}
            </p>
            <p className="text-xs text-ink-3">
              {t('statements.entries', { count: statement.transactionCount })}
              {' · '}{t('statements.generatedOn', { date: formatIstDate(statement.generatedAt) })}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-sm text-ink-3">
              {t('statements.closingBalance')}{' '}
              <MoneyText value={statement.closingBalance} className="font-semibold text-ink" />
            </span>
            <a href={collectionApi.statementPageUrl(pumpId, statement.id)} target="_blank" rel="noopener noreferrer"
              className="ui-btn ui-btn-secondary inline-flex items-center gap-1.5">
              <Printer size={14} aria-hidden="true" />{t('statements.open')}
            </a>
          </div>
        </li>
      ))}
    </ul>
  )
}
