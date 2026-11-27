import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { StatementLine } from '../../api/bankReconciliationApi'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { MoneyText } from '../../components/AmountText'
import { StatusBadge } from '../../components/StatusBadge'
import { formatIstDate } from '../../utils/date'
import { lineAmount, lineStatusBadge } from './reconciliationFormat'
import { MatchedRecordText } from './MatchedRecordText'
import { StatementLineButtons } from './StatementLineButtons'
import type { StatementLineActions } from './useStatementLineActions'

function LineAmount({ line }: { line: StatementLine }) {
  const { amount, isCredit } = lineAmount(line)
  return <MoneyText value={isCredit ? amount : -amount} showSign tone={isCredit ? 'ok' : 'bad'} className="font-semibold" />
}

function LineStatus({ line }: { line: StatementLine }) {
  return <StatusBadge {...lineStatusBadge(line.matchStatus)} />
}

function Narration({ line }: { line: StatementLine }) {
  const { t } = useTranslation('reconciliation')
  return (
    <div className="min-w-0">
      <p className="break-words text-sm text-ink">{line.narration || '—'}</p>
      {line.utrReference && <p className="text-xs text-ink-3">{t('lines.utr', { utr: line.utrReference })}</p>}
    </div>
  )
}

function lineColumns(t: TFunction<'reconciliation'>, actions: StatementLineActions): DataTableColumn<StatementLine>[] {
  return [
    { key: 'date', header: t('lines.date'), cell: (line) => <span className="whitespace-nowrap">{formatIstDate(line.txnDate)}</span> },
    { key: 'narration', header: t('lines.narration'), cell: (line) => <Narration line={line} /> },
    { key: 'amount', header: t('lines.amount'), numeric: true, cell: (line) => <LineAmount line={line} /> },
    { key: 'status', header: t('lines.status'), cell: (line) => <LineStatus line={line} /> },
    { key: 'match', header: t('lines.matchedTo'), cell: (line) => <MatchedRecordText line={line} /> },
    { key: 'actions', header: <span className="sr-only">{t('lines.actions')}</span>, align: 'right', cell: (line) => <StatementLineButtons line={line} actions={actions} /> },
  ]
}

interface StatementLineViewsProps {
  lines: StatementLine[]
  actions: StatementLineActions
  emptyMessage: string
}

/** Cards on phones and a table from md up, over the same lines. */
export function StatementLineViews({ lines, actions, emptyMessage }: StatementLineViewsProps) {
  const { t } = useTranslation('reconciliation')
  return (
    <>
      <ul className="md:hidden divide-y divide-border border-t border-border" aria-label={t('lines.caption')}>
        {lines.length === 0 ? (
          <li className="ui-empty px-4 py-6">{emptyMessage}</li>
        ) : lines.map((line) => (
          <li key={line.id} data-testid="statement-line-card" className="space-y-2 bg-surface px-4 py-3">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-xs text-ink-3">{formatIstDate(line.txnDate)}</p>
                <Narration line={line} />
              </div>
              <LineAmount line={line} />
            </div>
            <LineStatus line={line} />
            <MatchedRecordText line={line} />
            <StatementLineButtons line={line} actions={actions} stacked />
          </li>
        ))}
      </ul>
      <DataTable
        caption={t('lines.caption')}
        captionHidden
        columns={lineColumns(t, actions)}
        rows={lines}
        rowKey={(line) => line.id}
        empty={<p className="ui-empty px-6 py-6">{emptyMessage}</p>}
        className="hidden md:block rounded-none border-x-0 border-b-0"
      />
    </>
  )
}
