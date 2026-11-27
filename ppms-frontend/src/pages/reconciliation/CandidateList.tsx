import { useTranslation } from 'react-i18next'
import type { MatchedRecord, StatementLine } from '../../api/bankReconciliationApi'
import { MoneyText } from '../../components/AmountText'
import { SkeletonRows } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { formatIstDate } from '../../utils/date'
import { amountsDiffer, recordTypeLabel } from './reconciliationFormat'

interface CandidateListProps {
  line: StatementLine
  candidates: MatchedRecord[] | undefined
  isLoading: boolean
  error: string | null
  picked: MatchedRecord | null
  onPick: (record: MatchedRecord) => void
}

function isSame(a: MatchedRecord | null, b: MatchedRecord): boolean {
  return a !== null && a.type === b.type && a.id === b.id
}

/** The records a line could be matched to, one to pick; a different amount is flagged before it is picked. */
export function CandidateList({ line, candidates, isLoading, error, picked, onPick }: CandidateListProps) {
  const { t } = useTranslation('reconciliation')
  if (isLoading) return <SkeletonRows count={3} />
  if (error) return <p role="alert" className="ui-alert ui-alert-danger">{error}</p>
  if (!candidates || candidates.length === 0) return <p className="ui-empty py-4">{t('match.noCandidates')}</p>

  return (
    <fieldset>
      <legend className="ui-label">{t('match.candidates')}</legend>
      <ul className="ui-record-list rounded-card border border-border">
        {candidates.map((record) => (
          <li key={`${record.type}-${record.id}`} className="ui-record-row">
            <label className="flex min-h-11 w-full cursor-pointer items-start gap-3">
              <input type="radio" name="match-candidate" checked={isSame(picked, record)} onChange={() => onPick(record)}
                className="mt-1 accent-primary" />
              <span className="min-w-0 flex-1">
                <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="text-sm font-semibold text-ink">{recordTypeLabel(record)}</span>
                  {record.date && <span className="text-xs text-ink-3">{formatIstDate(record.date)}</span>}
                  {amountsDiffer(line, record) && <StatusBadge tone="warn" label={t('match.differentAmount')} />}
                </span>
                {record.reference && <span className="block text-xs text-ink-3">{t('lines.reference', { reference: record.reference })}</span>}
                {record.description && <span className="block text-xs text-ink-2 break-words">{record.description}</span>}
              </span>
              <MoneyText value={record.amount} className="text-sm font-semibold text-ink" />
            </label>
          </li>
        ))}
      </ul>
    </fieldset>
  )
}
