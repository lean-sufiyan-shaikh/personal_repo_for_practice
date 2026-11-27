import { useTranslation } from 'react-i18next'
import type { SettlementPaymentType } from '../../api/paymentSettlementApi'
import { formatIstDate } from '../../utils/date'
import type { CoveredDay } from './settlementAging'
import { fmtAmt, typeLabel } from './settlementFormat'

/** The collection days a settlement being recorded would clear, the last one marked when it is only partly cleared. */
export function CoveredDaysList({ type, days }: { type: SettlementPaymentType; days: CoveredDay[] }) {
  const { t } = useTranslation('settlements')
  if (days.length === 0) return null
  return (
    <div className="ui-card ui-card-muted px-4 py-3" aria-live="polite">
      <p className="text-xs font-semibold text-ink-2">{t('record.clears', { type: typeLabel(type) })}</p>
      <ul className="mt-1 space-y-0.5 text-xs text-ink-2">
        {days.map((day) => (
          <li key={day.date} className="flex justify-between gap-3">
            <span>{formatIstDate(day.date, { day: 'numeric', month: 'short' })}</span>
            <span className="tabular-nums">
              {day.covered < day.unsettled
                ? t('record.partOf', { covered: fmtAmt(day.covered), unsettled: fmtAmt(day.unsettled) })
                : fmtAmt(day.covered)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
