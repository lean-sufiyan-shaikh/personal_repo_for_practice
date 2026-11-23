import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { usePumpStore } from '../../store/usePumpStore'
import { useNow } from '../../hooks/useNow'
import { SkeletonRows } from '../../components/Skeleton'
import { MyShiftCard } from './active/MyShiftCard'
import { AddCreditEntryModal } from './modals/AddCreditEntryModal'

export default function MyShiftPage() {
  const pumpId = usePumpStore((state) => state.selectedPumpId)
  const now = useNow(30_000)
  const [shiftIdForCredit, setShiftIdForCredit] = useState<number | null>(null)

  const { data: myShifts = [], isLoading, isError } = useQuery({
    queryKey:        ['activeShifts', pumpId],
    queryFn:         () => shiftApi.getActiveShifts(pumpId!),
    enabled:         !!pumpId,
    refetchInterval: 60_000,
  })

  const { data: currentPrices = [] } = useQuery({
    queryKey: ['fuelPrices', pumpId],
    queryFn:  () => pumpApi.getCurrentPrices(pumpId!),
    enabled:  !!pumpId,
  })

  const shiftForCredit = myShifts.find((shift) => shift.id === shiftIdForCredit) ?? null

  return (
    <div className="ui-page ui-page--narrow flex min-h-full flex-col gap-4">
      <h1 className="ui-title-sm">My shift</h1>

      {!pumpId ? (
        <div className="ui-alert ui-alert-warning text-sm">
          You are not assigned to any pump. Ask the Owner or Admin to assign you.
        </div>
      ) : isLoading ? (
        <SkeletonRows count={3} />
      ) : isError ? (
        <div role="alert" className="ui-alert ui-alert-warning text-sm">
          Could not load your shift. Check your connection and refresh.
        </div>
      ) : myShifts.length === 0 ? (
        <div role="status" className="ui-card text-center">
          <p className="font-semibold text-ink">No open shift.</p>
          <p className="mt-1 text-sm text-ink-3">Ask your manager to open your shift.</p>
        </div>
      ) : (
        <>
          {myShifts.map((shift) => (
            <MyShiftCard key={shift.id} shift={shift} currentPrices={currentPrices} now={now} />
          ))}
          <div className="ui-sticky-action">
            {myShifts.map((shift) => (
              <button
                key={shift.id}
                type="button"
                onClick={() => setShiftIdForCredit(shift.id)}
                className="ui-btn ui-btn-primary min-h-14 w-full text-base"
              >
                + Credit sale{myShifts.length > 1 ? ` · ${shift.duName ?? `DU ${shift.duNumber}`}` : ''}
              </button>
            ))}
          </div>
        </>
      )}

      {shiftForCredit && (
        <AddCreditEntryModal shift={shiftForCredit} onClose={() => setShiftIdForCredit(null)} />
      )}
    </div>
  )
}
