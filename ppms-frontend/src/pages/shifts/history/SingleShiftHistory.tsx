import { useQuery } from '@tanstack/react-query'
import { shiftApi } from '../../../api/shiftApi'
import { ScrollRegion } from '../../../components/ScrollRegion'
import { SkeletonRows } from '../../../components/Skeleton'
import { parseApiError } from '../../../utils/apiError'
import { HistoryRow } from './HistoryRow'

/** One shift opened from a link elsewhere (a notification, a report row, the audit log). */
export function SingleShiftHistory({ pumpId, shiftId, onShowAll }: { pumpId: number; shiftId: number; onShowAll: () => void }) {
  const { data: shift, isLoading, error } = useQuery({
    queryKey: ['shift', pumpId, shiftId],
    queryFn: () => shiftApi.getShift(pumpId, shiftId),
  })

  return (
    <div>
      <div className="flex flex-wrap items-center gap-3 px-6 py-3 border-t border-slate-100 bg-slate-50/60">
        <span className="text-sm font-semibold text-ink">Shift #{shiftId}</span>
        <button type="button" onClick={onShowAll} className="ui-btn ui-btn-ghost ml-auto text-xs">Show all shifts</button>
      </div>
      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={1} /></div>
      ) : error || !shift ? (
        <p role="alert" className="ui-alert ui-alert-error m-4 text-sm">
          {parseApiError(error, `Shift #${shiftId} could not be found at this pump.`)}
        </p>
      ) : (
        <ScrollRegion label={`Shift ${shiftId}`} className="overflow-x-auto border-t border-slate-100">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-xs text-slate-500">
                <th scope="col" className="text-left px-5 py-3 font-medium hidden md:table-cell">ID</th>
                <th scope="col" className="text-left px-5 py-3 font-medium">Nozzle</th>
                <th scope="col" className="text-left px-5 py-3 font-medium hidden md:table-cell">Shift</th>
                <th scope="col" className="text-left px-5 py-3 font-medium">Operator</th>
                <th scope="col" className="text-left px-5 py-3 font-medium">Date</th>
                <th scope="col" className="text-left px-5 py-3 font-medium">Amount Due</th>
                <th scope="col" className="text-left px-5 py-3 font-medium">Status</th>
                <th scope="col" className="text-left px-5 py-3 font-medium hidden md:table-cell">Discrepancy</th>
                <th scope="col" className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              <HistoryRow shift={shift} />
            </tbody>
          </table>
        </ScrollRegion>
      )}
    </div>
  )
}
