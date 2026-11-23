import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import { DataTable } from '../../../components/DataTable'
import { SkeletonRows } from '../../../components/Skeleton'
import { parseApiError } from '../../../utils/apiError'
import { HistoryRow } from './HistoryRow'
import { historyColumns } from './historyColumns'

/** One shift opened from a link elsewhere (a notification, a report row, the audit log). */
export function SingleShiftHistory({ pumpId, shiftId, onShowAll }: { pumpId: number; shiftId: number; onShowAll: () => void }) {
  const { t } = useTranslation('shifts')
  const { data: shift, isLoading, error } = useQuery({
    queryKey: ['shift', pumpId, shiftId],
    queryFn: () => shiftApi.getShift(pumpId, shiftId),
  })

  return (
    <div>
      <div className="flex flex-wrap items-center gap-3 px-6 py-3 border-t border-border bg-surface-sunken">
        <span className="text-sm font-semibold text-ink">{t('history.shiftNumber', { id: shiftId })}</span>
        <button type="button" onClick={onShowAll} className="ui-btn ui-btn-ghost ml-auto text-xs">{t('history.showAll')}</button>
      </div>
      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={1} /></div>
      ) : error || !shift ? (
        <p role="alert" className="ui-alert ui-alert-error m-4 text-sm">
          {parseApiError(error, t('history.shiftNotFound', { id: shiftId }))}
        </p>
      ) : (
        <DataTable
          caption={t('history.shiftNumber', { id: shiftId })}
          captionHidden
          regionLabel={t('history.shiftRegion', { id: shiftId })}
          columns={historyColumns(t)}
          rows={[shift]}
          rowKey={(row) => row.id}
          renderRow={(row) => <HistoryRow shift={row} />}
          className="rounded-none border-x-0 border-b-0"
        />
      )}
    </div>
  )
}
