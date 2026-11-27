import { useTranslation } from 'react-i18next'
import type { DaySummary } from '../../../api/dayCloseApi'
import { StatusBadge } from '../../../components/StatusBadge'
import { formatIstDateTime } from '../../../utils/date'
import { checkText, flagBadge } from '../dayCloseText'
import { SalesSection } from './SalesSection'
import { CashSection } from './CashSection'
import { StockSection } from './StockSection'
import { CreditSection } from './CreditSection'

/** The four flagged sections of a day, with the warnings that stood when it was built. */
export function DaySummaryView({ summary }: { summary: DaySummary }) {
  const { t } = useTranslation('dayclose')
  return (
    <div className="space-y-(--density-gap) print:space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-sm text-ink-2">
        <span>{t('summary.overall')}</span>
        <StatusBadge {...flagBadge(summary.overallFlag)} />
        <span className="text-xs text-ink-3">
          {t('summary.builtAt', { time: formatIstDateTime(summary.builtAt), closed: summary.closedShiftCount })}
        </span>
      </div>
      {summary.warnings.length > 0 && (
        <ul aria-label={t('summary.warnings')} className="ui-alert ui-alert-warning space-y-0.5 text-sm">
          {summary.warnings.map((warning) => <li key={warning.code}>{checkText(warning)}</li>)}
        </ul>
      )}
      <div className="grid grid-cols-1 gap-(--density-gap) lg:grid-cols-2 print:grid-cols-2 print:gap-2">
        <SalesSection sales={summary.sales} />
        <CashSection cash={summary.cash} />
        <StockSection stock={summary.stock} />
        <CreditSection credit={summary.credit} />
      </div>
    </div>
  )
}
