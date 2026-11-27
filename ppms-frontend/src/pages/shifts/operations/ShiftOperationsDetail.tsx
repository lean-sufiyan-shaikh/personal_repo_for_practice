import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { BlindCountSummary } from './BlindCountSummary'
import { OperatorSharesTable } from './OperatorSharesTable'
import { ShiftTimelineList } from './ShiftTimelineList'
import { useShiftOperations } from './useShiftOperations'

/** On the shift detail: the blind count beside the expected amount, each operator's share and the shift's timeline. */
export function ShiftOperationsDetail({ shift }: { shift: Shift }) {
  const { t } = useTranslation(['shifts', 'common'])
  const { data } = useShiftOperations(shift.pumpId, shift.id)

  return (
    <div className="space-y-5">
      {shift.blindCount && (
        <section className="space-y-1" aria-label={t('blindCount.countedTitle')}>
          <h4 className="text-xs font-semibold text-ink-3">{t('blindCount.countedTitle')}</h4>
          <BlindCountSummary count={shift.blindCount} />
        </section>
      )}
      {data && <OperatorSharesTable shares={data.operatorShares} />}
      {data && data.timeline.length > 1 && <ShiftTimelineList events={data.timeline} />}
    </div>
  )
}
