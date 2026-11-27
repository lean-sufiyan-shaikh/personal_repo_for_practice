import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import type { MonthDay } from '../../api/monthCloseApi'
import { StatusBadge } from '../../components/StatusBadge'
import { PageSection } from '../../components/layout/PageSection'
import { formatIstDate } from '../../utils/date'
import { DAY_TONES } from './monthCloseFormat'

interface Props {
  days: MonthDay[]
  /** Reasons typed for days closed with the month as exceptions, by date; null when the month cannot be closed now. */
  reasons: Record<string, string> | null
  onReasonChange: (date: string, reason: string) => void
}

/** Every business day of the month: approved days in a line, the others each with a link and an exception reason. */
export function MonthDayChecklist({ days, reasons, onReasonChange }: Props) {
  const { t } = useTranslation('dayclose')
  const notReady = days.filter(day => day.statusCode !== 'APPROVED')
  const approved = days.length - notReady.length

  return (
    <PageSection title={t('month.daysTitle')} description={t('month.daysSummary', { approved, total: days.length })}>
      {notReady.length === 0 ? (
        <p className="text-sm text-ok">{t('month.allApproved')}</p>
      ) : (
        <ul className="divide-y divide-border">
          {notReady.map(day => (
            <li key={day.date} className="flex flex-col gap-2 py-2 sm:flex-row sm:items-center sm:gap-3">
              <div className="flex items-center gap-2 sm:w-64">
                <span className="text-sm font-medium text-ink">{formatIstDate(day.date)}</span>
                <StatusBadge tone={DAY_TONES[day.statusCode]} label={t(`month.dayStatus.${day.statusCode}`)} />
              </div>
              <Link to={day.fixPath} className="ui-btn ui-btn-ghost ui-btn-compact self-start print:hidden">
                {t('month.openDay')}
              </Link>
              {reasons && (
                <input type="text" aria-label={t('month.exceptionReasonFor', { date: formatIstDate(day.date) })}
                  placeholder={t('month.exceptionReason')} maxLength={300} value={reasons[day.date] ?? ''}
                  onChange={e => onReasonChange(day.date, e.target.value)} className="min-h-11 flex-1 text-sm" />
              )}
            </li>
          ))}
        </ul>
      )}
    </PageSection>
  )
}
