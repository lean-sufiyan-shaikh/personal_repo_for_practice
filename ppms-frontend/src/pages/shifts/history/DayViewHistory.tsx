import { useState } from 'react'
import { withToggled } from '../../../utils/setToggle'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import type { Shift } from '../../../types/shift'
import { SkeletonRows } from '../../../components/Skeleton'
import { formatIstDate, localDateInputValue } from '../../../utils/date'
import { parseApiError } from '../../../utils/apiError'
import { HistoryRow } from './HistoryRow'
import { historyColumns } from './historyColumns'
import { labelForDate } from '../shiftLabels'
import { DataTable } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { MoneyText } from '../../../components/AmountText'

// ── Day View ──────────────────────────────────────────────────────────────────

export function DayViewHistory({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('shifts')
  const todayStr     = localDateInputValue()
  const yesterdayStr = localDateInputValue(-1)
  const dayBeforeStr = localDateInputValue(-2)

  const { data: shifts = [], isLoading, error } = useQuery({
    queryKey: ['shiftHistory', pumpId, 'days', dayBeforeStr],
    queryFn:  () => shiftApi.getShiftHistory(pumpId, { from: dayBeforeStr }),
  })

  // Group by shiftDate
  const grouped = shifts.reduce<Record<string, Shift[]>>((acc, s) => {
    ;(acc[s.shiftDate] ??= []).push(s)
    return acc
  }, {})

  // Only show the 3 most recent days: today, yesterday, day before yesterday
  const visibleDates = [todayStr, yesterdayStr, dayBeforeStr].filter(d => grouped[d])

  // Today is open by default; rest start collapsed
  const [openDays, setOpenDays] = useState<Set<string>>(new Set([todayStr]))

  const toggle = (d: string) =>
    setOpenDays(prev => withToggled(prev, d))

  const fmtShortDate = (d: string) =>
    formatIstDate(d, { day: 'numeric', month: 'short', year: 'numeric' })

  if (isLoading) return <div className="px-5 py-4"><SkeletonRows count={4} /></div>
  if (error) return <p role="alert" className="ui-alert ui-alert-error m-4 text-sm">{parseApiError(error, t('history.loadFailed'))}</p>
  if (visibleDates.length === 0) {
    return <p className="ui-empty px-6 py-6">{t('history.dayEmpty')}</p>
  }

  return (
    <div className="divide-y divide-border">
      {visibleDates.map((date) => {
        const dayShifts = grouped[date]
        const isOpen    = openDays.has(date)
        const openCount = dayShifts.filter((sh) => sh.status === 'OPEN' || sh.status === 'OPEN_OVERDUE').length
        const totalAmt  = dayShifts.reduce((s, sh) => s + (sh.totalAmountDue ?? 0), 0)
        const label     = labelForDate(date)
        const isToday   = date === todayStr

        return (
          <div key={date}>
            {/* Day accordion header */}
            <button
              onClick={() => toggle(date)}
              className="w-full flex items-center justify-between px-6 py-3 hover:bg-bg transition-colors text-left"
            >
              <div className="flex items-center gap-3">
                <span className={`text-sm font-semibold ${isToday ? 'text-primary' : 'text-ink'}`}>
                  {label}
                </span>
                <span className="text-xs text-ink-3">{fmtShortDate(date)}</span>
                <span className="text-xs text-ink-3">
                  · {t('history.shiftCount', { count: dayShifts.length })}{openCount > 0 && t('history.stillOpen', { count: openCount })}
                </span>
                {totalAmt > 0 && (
                  <span className="text-xs font-medium text-ink-2">
                    · <MoneyText value={totalAmt} />
                  </span>
                )}
                {isToday && (
                  <StatusBadge tone="info" label={t('labels.today')} />
                )}
              </div>
              <ChevronDown size={14} strokeWidth={2} className={`ui-accordion-arrow ${isOpen ? 'ui-accordion-arrow--open' : ''}`} />
            </button>

            {/* Day shift table */}
            {isOpen && (
              <DataTable
                caption={t('history.shiftsFor', { label })}
                captionHidden
                regionLabel={t('history.shiftsForDay')}
                columns={historyColumns(t)}
                rows={dayShifts}
                rowKey={(shift) => shift.id}
                renderRow={(shift) => <HistoryRow shift={shift} />}
                className="ui-accordion-content rounded-none border-x-0 border-b-0"
              />
            )}
          </div>
        )
      })}
    </div>
  )
}
