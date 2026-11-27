import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import type { StatementImportSummary } from '../../api/bankReconciliationApi'

interface ImportResultNoticeProps {
  result: StatementImportSummary
  /** Import says how many lines were added and skipped; auto-match only how many it matched. */
  kind: 'import' | 'autoMatch'
  onDismiss: () => void
}

/** What an import or auto-match just did: lines matched and suggested, and lines skipped as duplicates or unreadable. */
export function ImportResultNotice({ result, kind, onDismiss }: ImportResultNoticeProps) {
  const { t } = useTranslation(['reconciliation', 'common'])
  const parts = [
    kind === 'import' && t('result.lines', { count: result.totalLines }),
    t('result.autoMatched', { count: result.autoMatchedLines ?? 0 }),
    t('result.autoSuggested', { count: result.autoSuggestedLines ?? 0 }),
    (result.skippedDuplicateLines ?? 0) > 0 && t('result.duplicates', { count: result.skippedDuplicateLines ?? 0 }),
    (result.skippedInvalidRows ?? 0) > 0 && t('result.invalid', { count: result.skippedInvalidRows ?? 0 }),
  ].filter((part): part is string => typeof part === 'string')

  return (
    <div role="status" className="ui-alert ui-alert-success flex items-start gap-3">
      <div className="min-w-0 flex-1">
        <p className="font-semibold">{kind === 'import' ? t('result.importedTitle') : t('result.autoMatchTitle')}</p>
        <p className="text-sm">{parts.join(' · ')}</p>
      </div>
      <button type="button" onClick={onDismiss} aria-label={t('result.dismiss')} className="ui-btn-icon">
        <X size={16} aria-hidden="true" />
      </button>
    </div>
  )
}
