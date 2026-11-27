import { useTranslation } from 'react-i18next'
import { FormField } from '../../../components/FormField'
import { addDays, formatIstDate } from '../../../utils/date'
import type { CountDay, CountTiming } from './safeCountDate'

interface Props {
  today: string
  day: CountDay
  onDayChange: (day: CountDay) => void
  earlierDate: string
  onEarlierDateChange: (date: string) => void
  /** The business day the count is for, or '' while an earlier day is not picked yet. */
  date: string
  timing: CountTiming
  canBackDate: boolean
  cutoffTime: string
  reason: string
  onReasonChange: (reason: string) => void
}

/** Which business day the count is for, what that means (late or back-dated), and the reason a back-dated count needs. */
export function SafeCountDayField({
  today, day, onDayChange, earlierDate, onEarlierDateChange, date, timing, canBackDate, cutoffTime, reason, onReasonChange,
}: Props) {
  const { t } = useTranslation('cash')
  const yesterday = addDays(today, -1)
  const days: CountDay[] = canBackDate ? ['today', 'yesterday', 'earlier'] : ['today', 'yesterday']
  const labels: Record<CountDay, string> = {
    today: t('safe.count.forToday'),
    yesterday: t('safe.count.forYesterday', { date: formatIstDate(yesterday, { day: 'numeric', month: 'short' }) }),
    earlier: t('safe.count.forEarlier'),
  }
  const shownDate = date ? formatIstDate(date) : ''

  return (
    <div className="space-y-3">
      <div className="ui-chip-row" role="group" aria-label={t('safe.count.forLabel')}>
        {days.map((choice) => (
          <button key={choice} type="button" className="ui-chip" aria-pressed={day === choice} onClick={() => onDayChange(choice)}>
            {labels[choice]}
          </button>
        ))}
      </div>

      {day === 'earlier' && (
        <FormField label={t('safe.count.dateLabel')}>
          <input type="date" value={earlierDate} max={addDays(today, -2)} onChange={(e) => onEarlierDateChange(e.target.value)} />
        </FormField>
      )}

      {timing === 'NEXT_MORNING' && (
        <p className="ui-alert ui-alert-info text-sm">{t('safe.count.lateNote', { date: shownDate, time: cutoffTime })}</p>
      )}
      {timing === 'BACK_DATED' && date && !canBackDate && (
        <p role="alert" className="ui-alert ui-alert-warning text-sm">{t('safe.count.pastCutoffNote', { date: shownDate, time: cutoffTime })}</p>
      )}
      {timing === 'BACK_DATED' && date && canBackDate && (
        <>
          <p className="ui-alert ui-alert-warning text-sm">{t('safe.count.backDatedNote', { date: shownDate })}</p>
          <FormField label={t('safe.count.reason')}>
            <textarea value={reason} onChange={(e) => onReasonChange(e.target.value)} rows={2} maxLength={500} className="resize-none" />
          </FormField>
        </>
      )}
    </div>
  )
}
