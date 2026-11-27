import { useTranslation } from 'react-i18next'
import type { StatementImportSummary } from '../../api/bankReconciliationApi'
import { PageSection } from '../../components/layout/PageSection'
import { formatIstDate, formatIstDateTime } from '../../utils/date'

interface StatementImportPickerProps {
  imports: StatementImportSummary[]
  selectedId: number | null
  onSelect: (id: number) => void
}

function periodOf(statement: StatementImportSummary): string | null {
  if (!statement.statementFromDate || !statement.statementToDate) return null
  return `${formatIstDate(statement.statementFromDate)} – ${formatIstDate(statement.statementToDate)}`
}

/** Imported statements, newest first; picking one shows its lines below. */
export function StatementImportPicker({ imports, selectedId, onSelect }: StatementImportPickerProps) {
  const { t } = useTranslation('reconciliation')
  return (
    <PageSection title={t('imports.title')} flush>
      <ul className="ui-record-list" aria-label={t('imports.title')}>
        {imports.map((statement) => {
          const period = periodOf(statement)
          return (
            <li key={statement.id}>
              <button type="button" aria-pressed={statement.id === selectedId} onClick={() => onSelect(statement.id)}
                className={`ui-record-row w-full min-h-11 text-left ${statement.id === selectedId ? 'bg-surface-sunken' : ''}`}>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-ink">
                    {statement.bankName}{statement.accountNumber ? ` · ${statement.accountNumber}` : ''}
                  </span>
                  <span className="block text-xs text-ink-3">
                    {[period, t('imports.importedAt', { when: formatIstDateTime(statement.importedAt) })].filter(Boolean).join(' · ')}
                  </span>
                </span>
                <span className="text-xs text-ink-2 whitespace-nowrap">
                  {t('imports.matchedOf', { matched: statement.matchedLines, total: statement.totalLines })}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </PageSection>
  )
}
