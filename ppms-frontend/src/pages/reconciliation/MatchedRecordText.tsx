import { useTranslation } from 'react-i18next'
import type { StatementLine } from '../../api/bankReconciliationApi'
import { formatCurrency } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import { recordTypeLabel } from './reconciliationFormat'

/** What the line is matched to, or the suggestion for it, and the note kept with the match or ignore. */
export function MatchedRecordText({ line }: { line: StatementLine }) {
  const { t } = useTranslation('reconciliation')
  const record = line.matchedRecord
  const details = record && [
    recordTypeLabel(record),
    record.date && formatIstDate(record.date),
    record.amount !== null && formatCurrency(record.amount),
    record.reference,
  ].filter(Boolean).join(' · ')

  return (
    <div className="min-w-0 space-y-0.5 text-xs">
      {record && (
        <p className="text-ink-2">
          {line.matchStatus === 'SUGGESTED' ? t('lines.suggestion', { record: details }) : details}
        </p>
      )}
      {record?.description && <p className="text-ink-3">{record.description}</p>}
      {line.matchNotes && <p className="text-ink-3">{t('lines.note', { note: line.matchNotes })}</p>}
    </div>
  )
}
