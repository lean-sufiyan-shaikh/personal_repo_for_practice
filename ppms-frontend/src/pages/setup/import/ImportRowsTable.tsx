import { AlertTriangle, Check, CircleSlash, type LucideIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ImportReport, ImportRow, ImportRowResult, RowOutcome } from '../../../api/importApi'
import { DataTable, type DataTableColumn } from '../../../components/DataTable'
import { StatusBadge, type StatusTone } from '../../../components/StatusBadge'
import type { ImportTemplate } from './importTemplates'
import { useImportTexts } from './importTexts'

const OUTCOME_BADGE: Record<RowOutcome, { tone: StatusTone; icon: LucideIcon }> = {
  CREATE: { tone: 'ok', icon: Check },
  SKIP: { tone: 'neutral', icon: CircleSlash },
  ERROR: { tone: 'bad', icon: AlertTriangle },
}

/** Rows with problems first, so the owner sees what to fix without scrolling. */
const OUTCOME_ORDER: Record<RowOutcome, number> = { ERROR: 0, SKIP: 1, CREATE: 2 }

interface ImportRowsTableProps {
  template: ImportTemplate
  report: ImportReport
  rows: ImportRow[]
  /** After the import the badges say what happened rather than what will. */
  finished?: boolean
}

/** Every row of the file with its outcome and, cell by cell, what is wrong; stacked cards on phones. */
export function ImportRowsTable({ template, report, rows, finished = false }: ImportRowsTableProps) {
  const { t } = useTranslation('setup')
  const { issueText } = useImportTexts()
  const rowByNumber = new Map(rows.map((row) => [row.rowNumber, row]))
  const [firstColumn, secondColumn] = template.columns
  const sorted = [...report.rows].sort((a, b) => OUTCOME_ORDER[a.outcome] - OUTCOME_ORDER[b.outcome] || a.rowNumber - b.rowNumber)

  const label = (result: ImportRowResult) => {
    const row = rowByNumber.get(result.rowNumber)
    return [row?.[firstColumn.field], secondColumn ? row?.[secondColumn.field] : null].filter(Boolean).join(' · ')
  }

  const columns: DataTableColumn<ImportRowResult>[] = [
    { key: 'row', header: t('import.table.row'), numeric: true, className: 'w-16', cell: (result) => result.rowNumber },
    {
      key: 'status',
      header: t('import.table.status'),
      className: 'w-40',
      cell: (result) => {
        const badge = OUTCOME_BADGE[result.outcome]
        const text = t(finished ? `import.doneOutcomes.${result.outcome}` : `import.outcomes.${result.outcome}`)
        return <StatusBadge tone={badge.tone} icon={badge.icon} label={text} />
      },
    },
    {
      key: 'details',
      header: t('import.table.details'),
      cell: (result) => (
        <div className="min-w-0 space-y-1">
          <p className="truncate text-sm font-medium text-ink">{label(result)}</p>
          {result.issues.length > 0 && (
            <ul className={`space-y-0.5 text-xs ${result.outcome === 'ERROR' ? 'text-bad' : 'text-ink-2'}`}>
              {result.issues.map((issue, index) => <li key={`${issue.column}-${issue.code}-${index}`}>{issueText(issue)}</li>)}
            </ul>
          )}
        </div>
      ),
    },
  ]

  return (
    <DataTable
      caption={t('import.table.caption')}
      captionHidden
      columns={columns}
      rows={sorted}
      rowKey={(result) => result.rowNumber}
      scroll
      cardsOnPhone
    />
  )
}
