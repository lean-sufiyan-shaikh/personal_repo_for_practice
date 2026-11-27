import { useTranslation } from 'react-i18next'
import type { StatementLine } from '../../api/bankReconciliationApi'
import { formatCurrency } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import { lineAmount } from './reconciliationFormat'
import type { StatementLineActions } from './useStatementLineActions'

interface StatementLineButtonsProps {
  line: StatementLine
  actions: StatementLineActions
  /** Full-width buttons for the phone card. */
  stacked?: boolean
}

/** What can be done with a line in its status: accept a suggestion, match by hand, ignore with a reason, or undo. */
export function StatementLineButtons({ line, actions, stacked = false }: StatementLineButtonsProps) {
  const { t } = useTranslation('reconciliation')
  const name = t('lines.lineName', { date: formatIstDate(line.txnDate), amount: formatCurrency(lineAmount(line).amount) })
  const size = stacked ? 'min-h-11 w-full' : 'ui-btn-compact'
  const status = line.matchStatus

  return (
    <div className={stacked ? 'grid grid-cols-1 gap-2 sm:grid-cols-3' : 'flex flex-wrap justify-end gap-1.5'}>
      {status === 'SUGGESTED' && (
        <button type="button" disabled={actions.isBusy} onClick={() => actions.accept(line)}
          aria-label={t('actions.acceptFor', { line: name })} className={`ui-btn ui-btn-primary ${size}`}>
          {t('actions.accept')}
        </button>
      )}
      {(status === 'SUGGESTED' || status === 'UNMATCHED') && (
        <>
          <button type="button" disabled={actions.isBusy} onClick={() => actions.startMatch(line)}
            aria-label={status === 'SUGGESTED' ? t('actions.matchOtherFor', { line: name }) : t('actions.matchFor', { line: name })}
            className={`ui-btn ${status === 'UNMATCHED' ? 'ui-btn-primary' : 'ui-btn-secondary'} ${size}`}>
            {status === 'SUGGESTED' ? t('actions.matchOther') : t('actions.match')}
          </button>
          <button type="button" disabled={actions.isBusy} onClick={() => actions.startIgnore(line)}
            aria-label={t('actions.ignoreFor', { line: name })} className={`ui-btn ui-btn-ghost ${size}`}>
            {t('actions.ignore')}
          </button>
        </>
      )}
      {(status === 'MATCHED' || status === 'IGNORED') && (
        <button type="button" disabled={actions.isBusy} onClick={() => actions.unmatch(line)}
          aria-label={status === 'MATCHED' ? t('actions.unmatchFor', { line: name }) : t('actions.restoreFor', { line: name })} className={`ui-btn ui-btn-secondary ${size}`}>
          {status === 'MATCHED' ? t('actions.unmatch') : t('actions.restore')}
        </button>
      )}
    </div>
  )
}
