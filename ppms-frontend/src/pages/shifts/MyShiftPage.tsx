import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { usePumpStore } from '../../store/usePumpStore'
import { useNow } from '../../hooks/useNow'
import { SkeletonRows } from '../../components/Skeleton'
import { MyShiftCard } from './active/MyShiftCard'
import { AddCreditEntryModal } from './modals/AddCreditEntryModal'
import { RecordDropModal } from './drops/RecordDropModal'
import { BlindCountModal } from './operations/BlindCountModal'
import { ShiftOperationsModal } from './operations/ShiftOperationsModal'
import { Page } from '../../components/layout/Page'
import { PageHeader } from '../../components/layout/PageHeader'
import { StickyActions } from '../../components/layout/StickyActions'

export default function MyShiftPage() {
  const { t } = useTranslation(['shifts', 'common'])
  const pumpId = usePumpStore((state) => state.selectedPumpId)
  const now = useNow(30_000)
  const [shiftIdForCredit, setShiftIdForCredit] = useState<number | null>(null)
  const [shiftIdForDrop, setShiftIdForDrop] = useState<number | null>(null)
  const [shiftIdForCount, setShiftIdForCount] = useState<number | null>(null)
  const [shiftIdForBreak, setShiftIdForBreak] = useState<number | null>(null)

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
  const shiftForDrop = myShifts.find((shift) => shift.id === shiftIdForDrop) ?? null
  const shiftForCount = myShifts.find((shift) => shift.id === shiftIdForCount) ?? null
  const shiftForBreak = myShifts.find((shift) => shift.id === shiftIdForBreak) ?? null
  const duSuffix = (duName: string | null, duNumber: number | null) =>
    myShifts.length > 1 ? ` · ${duName ?? `DU ${duNumber}`}` : ''

  return (
    <Page className="min-h-full">
      <PageHeader title={t('myShift.title')} />

      {!pumpId ? (
        <div className="ui-alert ui-alert-warning text-sm">
          {t('common:notices.notAssigned')}
        </div>
      ) : isLoading ? (
        <SkeletonRows count={3} />
      ) : isError ? (
        <div role="alert" className="ui-alert ui-alert-warning text-sm">
          {t('myShift.loadFailed')}
        </div>
      ) : myShifts.length === 0 ? (
        <div role="status" className="ui-card text-center">
          <p className="font-semibold text-ink">{t('myShift.noOpenShift')}</p>
          <p className="mt-1 text-sm text-ink-3">{t('myShift.askManager')}</p>
        </div>
      ) : (
        <>
          {myShifts.map((shift) => (
            <MyShiftCard key={shift.id} shift={shift} currentPrices={currentPrices} now={now}
              onCountCash={() => setShiftIdForCount(shift.id)} onBreak={() => setShiftIdForBreak(shift.id)} />
          ))}
          <StickyActions>
            {myShifts.map((shift) => (
              <div key={shift.id} className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => setShiftIdForDrop(shift.id)}
                  className="ui-btn ui-btn-secondary min-h-14 w-full text-base"
                >
                  {t('myShift.dropCash')}{duSuffix(shift.duName, shift.duNumber)}
                </button>
                <button
                  type="button"
                  onClick={() => setShiftIdForCredit(shift.id)}
                  className="ui-btn ui-btn-primary min-h-14 w-full text-base"
                >
                  {t('myShift.addCreditSale')}{duSuffix(shift.duName, shift.duNumber)}
                </button>
              </div>
            ))}
          </StickyActions>
        </>
      )}

      {shiftForCredit && (
        <AddCreditEntryModal shift={shiftForCredit} onClose={() => setShiftIdForCredit(null)} />
      )}
      {shiftForDrop && <RecordDropModal shift={shiftForDrop} onClose={() => setShiftIdForDrop(null)} />}
      {shiftForCount && <BlindCountModal shift={shiftForCount} onClose={() => setShiftIdForCount(null)} />}
      {shiftForBreak && <ShiftOperationsModal shift={shiftForBreak} onClose={() => setShiftIdForBreak(null)} />}
    </Page>
  )
}
