import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { ChevronDown, Plus, History } from 'lucide-react'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { usePumpStore } from '../../store/usePumpStore'
import { useAuthStore } from '../../store/authStore'
import { canPerformAction } from '../../permissions/permissions'
import type { Shift } from '../../types/shift'
import OpenShiftModal from './OpenShiftModal'
import CloseShiftModal from './CloseShiftModal'
import BackfillShiftModal from './BackfillShiftModal'
import { SkeletonRows } from '../../components/Skeleton'
import { Reveal } from '../../components/Reveal'
import { RefreshIndicator } from '../../components/RefreshIndicator'
import { ShiftRow } from './active/ShiftRow'
import { AllShiftsHistory } from './history/AllShiftsHistory'
import { DayViewHistory } from './history/DayViewHistory'
import { DiscrepanciesHistory } from './history/DiscrepanciesHistory'
import { SingleShiftHistory } from './history/SingleShiftHistory'
import { AddCreditEntryModal } from './modals/AddCreditEntryModal'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'

type HistoryTab = 'day' | 'all' | 'discrepancies'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

function dateParam(value: string | null): string | undefined {
  return value && ISO_DATE.test(value) ? value : undefined
}

/**
 * Shift history filters kept in the address so other pages can link straight to them:
 * ?shift=123, ?history=all&from=&to=&fuel=PETROL, ?history=discrepancies&from=&to=&type=SHORT.
 */
function useHistoryFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const shiftText = searchParams.get('shift')
  const shiftId = shiftText && /^\d+$/.test(shiftText) ? Number(shiftText) : null
  const historyText = searchParams.get('history')
  const tab: HistoryTab = shiftId !== null ? 'all'
    : historyText === 'all' || historyText === 'discrepancies' ? historyText : 'day'
  const typeText = searchParams.get('type')
  const fuelText = searchParams.get('fuel')

  const showTab = (next: HistoryTab) => setSearchParams((params) => {
    const updated = new URLSearchParams(params)
    for (const name of ['shift', 'from', 'to', 'fuel', 'type']) updated.delete(name)
    updated.set('history', next)
    return updated
  }, { replace: true })

  const clearFuel = () => setSearchParams((params) => {
    const updated = new URLSearchParams(params)
    updated.delete('fuel')
    return updated
  }, { replace: true })

  return {
    shiftId,
    tab,
    from: dateParam(searchParams.get('from')),
    to: dateParam(searchParams.get('to')),
    fuelType: fuelText && /^[A-Z_]+$/.test(fuelText) ? fuelText : undefined,
    discrepancyType: typeText === 'SHORT' || typeText === 'OVER' ? typeText as 'SHORT' | 'OVER' : undefined,
    linkedFromElsewhere: shiftId !== null || historyText !== null,
    showTab,
    clearFuel,
  }
}

// ── Main page ─────────────────────────────────────────────────────────────────

const ACTIVE_SHIFT_COLUMNS: DataTableColumn<Shift>[] = [
  { key: 'nozzle', header: 'Nozzle' },
  { key: 'operator', header: 'Operator' },
  { key: 'window', header: 'Window', className: 'hidden md:table-cell' },
  { key: 'started', header: 'Started', className: 'hidden md:table-cell' },
  { key: 'prices', header: 'Prices (₹)', className: 'hidden md:table-cell' },
  { key: 'status', header: 'Status' },
  { key: 'actions', header: <span className="sr-only">Actions</span> },
]

export default function ShiftsPage() {
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const canOpenShift  = canPerformAction('shift:open', user?.role)
  const canCloseShift = canPerformAction('shift:close', user?.role)
  const canViewDiscrepancies = canPerformAction('shift:view-discrepancies', user?.role)
  const seesOnlyOwnShifts = user?.role === 'OPERATOR'
  const historyTabs = canViewDiscrepancies ? (['day', 'all', 'discrepancies'] as const) : (['day', 'all'] as const)

  const { selectedPumpId } = usePumpStore()
  const [showOpenModal,     setShowOpenModal]     = useState(false)
  const [showBackfillModal, setShowBackfillModal] = useState(false)
  const [shiftToClose,      setShiftToClose]      = useState<Shift | null>(null)
  const [shiftToHandOver,   setShiftToHandOver]   = useState<Shift | null>(null)
  const [shiftIdForCredit,  setShiftIdForCredit]  = useState<number | null>(null)
  const [historyOpen,    setHistoryOpen]    = useState(true)
  const historyFilters = useHistoryFilters()
  const historyTab = historyFilters.tab === 'discrepancies' && !canViewDiscrepancies ? 'day' : historyFilters.tab
  const historyRef = useRef<HTMLDivElement>(null)
  const { linkedFromElsewhere, shiftId: linkedShiftId } = historyFilters

  useEffect(() => {
    if (linkedFromElsewhere) historyRef.current?.scrollIntoView?.({ block: 'start' })
  }, [linkedFromElsewhere, linkedShiftId])
  const [closedShiftPrefill, setClosedShiftPrefill] = useState<{ duId: number; nozzleIds: number[] } | null>(null)

  const { data: rawPumps = [], isLoading: pumpsLoading } = useQuery({
    queryKey: ['myPumps'],
    queryFn:  pumpApi.getMyPumps,
    enabled:  isOwnerOrAdmin,
  })

  // Sort alphabetically once
  const sortedPumps = [...rawPumps].sort((a, b) => a.name.localeCompare(b.name))

  const pumpId = selectedPumpId

  const { data: activeShifts = [], isLoading: activeLoading, isFetching: activeFetching, dataUpdatedAt: activeUpdatedAt } = useQuery({
    queryKey:      ['activeShifts', pumpId],
    queryFn:       () => shiftApi.getActiveShifts(pumpId!),
    enabled:       !!pumpId,
    refetchInterval: 60_000,
  })


  // ── Early exits ──────────────────────────────────────────────────────────

  if (isOwnerOrAdmin && !pumpsLoading && sortedPumps.length === 0 && rawPumps.length === 0) {
    return (
      <div className="ui-page ui-page--narrow">
        <div className="ui-alert ui-alert-warning p-6 text-center">
          <p className="text-warn-strong font-medium mb-2">No pumps set up yet</p>
          <p className="text-warn text-sm mb-4">
            Create a pump location first, then add nozzles and set fuel prices.
          </p>
          <Link to="/dashboard/setup"
            className="ui-btn ui-btn-primary inline-flex">
            Go to Setup
          </Link>
        </div>
      </div>
    )
  }

  if (!isOwnerOrAdmin && !pumpId) {
    return (
      <div className="ui-page ui-page--narrow">
        <div className="ui-alert ui-alert-warning text-sm">
          You are not assigned to any pump. Ask the Owner or Admin to assign you.
        </div>
      </div>
    )
  }

  const selectedPump = sortedPumps.find((p) => p.id === pumpId)
  const shiftForCredit = activeShifts.find((shift) => shift.id === shiftIdForCredit) ?? null

  return (
    <div className="ui-page ui-page--narrow space-y-6">

      {/* Page title */}
      <Reveal delay={60}>
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">Shift control</p>
          <h1 className="ui-title-sm">Shifts</h1>
          <p className="ui-subtitle">Open and close operator shifts, record meter readings and payment collection.</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Open now</span>
            <span className="ui-section-meta-value">{activeLoading ? '…' : activeShifts.length}</span>
          </div>
        </div>
      </div>
      </Reveal>

      {!pumpId ? (
        <div className="ui-card text-center text-sm text-ink-3">
          No pump selected. Use the pump selector in the top navigation bar.
        </div>
      ) : (
        <>
          {/* ── Active Shifts ──────────────────────────────────────────────── */}
          <div className="ui-card overflow-hidden p-0">
            <div className="ui-toolbar">
              <div className="flex items-center gap-2.5">
                <h2 className="ui-toolbar-title">{seesOnlyOwnShifts ? 'My Active Shifts' : 'Active Shifts'}</h2>
                <RefreshIndicator isFetching={activeFetching} dataUpdatedAt={activeUpdatedAt ?? 0} />
              </div>
              {selectedPump && (
                <p className="text-xs text-ink-3">{selectedPump.name}</p>
              )}
              <div className="ui-toolbar-actions ml-auto">
                {isOwnerOrAdmin && (
                  <button
                    onClick={() => setShowBackfillModal(true)}
                    className="ui-btn ui-btn-secondary inline-flex items-center gap-1.5"
                    title="Enter historical shift data for past dates"
                  >
                    <History size={14} strokeWidth={2} />
                    Backfill Shift
                  </button>
                )}
                {canOpenShift && (
                  <button
                    onClick={() => setShowOpenModal(true)}
                    className="ui-btn ui-btn-primary inline-flex items-center gap-1.5"
                  >
                    <Plus size={14} strokeWidth={2.5} />
                    Open Shift
                  </button>
                )}
              </div>
            </div>

            {activeLoading ? (
              <div className="px-5 py-4"><SkeletonRows count={3} /></div>
            ) : activeShifts.length === 0 ? (
              <div className="ui-empty p-6">
                {canOpenShift
                  ? 'No active shifts. Tap Open Shift to start one.'
                  : seesOnlyOwnShifts
                  ? 'No open shift. Ask your manager to open your shift.'
                  : 'No active shifts.'}
              </div>
            ) : (
              <DataTable
                caption="Active shifts"
                captionHidden
                columns={ACTIVE_SHIFT_COLUMNS}
                rows={activeShifts}
                rowKey={(shift) => shift.id}
                cardsOnPhone
                className="rounded-none border-x-0 border-b-0"
                renderRow={(shift) => (
                  <ShiftRow
                    shift={shift}
                    onClose={canCloseShift ? () => setShiftToClose(shift) : undefined}
                    onHandOver={canCloseShift ? () => setShiftToHandOver(shift) : undefined}
                    onAddCredit={() => setShiftIdForCredit(shift.id)}
                  />
                )}
              />
            )}
          </div>

          {/* ── Shift History ──────────────────────────────────────────────── */}
          <div ref={historyRef} className="ui-card overflow-hidden p-0">
            {/* Header / toggle */}
            <button
              onClick={() => setHistoryOpen((v) => !v)}
              className="w-full flex items-center justify-between px-6 py-4 hover:bg-bg transition-colors"
            >
              <span className="text-base font-semibold text-ink">{seesOnlyOwnShifts ? 'My Shift History' : 'Shift History'}</span>
              <ChevronDown size={14} strokeWidth={2} className={`ui-accordion-arrow ${historyOpen ? 'ui-accordion-arrow--open' : ''}`} />
            </button>

            {historyOpen && (
              <div className="ui-accordion-content">
                {/* Tab row */}
                <div className="ui-tabbar">
                  {historyTabs.map((tab) => (
                    <button
                      key={tab}
                      onClick={() => historyFilters.showTab(tab)}
                      aria-pressed={historyTab === tab}
                      className={`ui-tabbar__button ${historyTab === tab ? 'ui-tabbar__button--active' : ''}`}
                    >
                      {tab === 'day' ? 'By Day' : tab === 'all' ? 'All Shifts' : 'Discrepancies'}
                    </button>
                  ))}
                </div>

                <div key={historyTab} className="ui-tab-content">
                  {!pumpId ? null : historyFilters.shiftId !== null ? (
                    <SingleShiftHistory
                      pumpId={pumpId}
                      shiftId={historyFilters.shiftId}
                      onShowAll={() => historyFilters.showTab('all')}
                    />
                  ) : historyTab === 'day' ? (
                    <DayViewHistory pumpId={pumpId} />
                  ) : historyTab === 'discrepancies' ? (
                    <DiscrepanciesHistory
                      key={`${historyFilters.from}|${historyFilters.to}|${historyFilters.discrepancyType}`}
                      pumpId={pumpId}
                      initialFrom={historyFilters.from}
                      initialTo={historyFilters.to}
                      initialType={historyFilters.discrepancyType}
                    />
                  ) : (
                    <AllShiftsHistory
                      key={`${historyFilters.from}|${historyFilters.to}|${historyFilters.fuelType}`}
                      pumpId={pumpId}
                      initialFrom={historyFilters.from}
                      initialTo={historyFilters.to}
                      fuelType={historyFilters.fuelType}
                      onClearFuel={historyFilters.clearFuel}
                    />
                  )}
                </div>
              </div>
            )}
          </div>
        </>
      )}

      {showOpenModal && pumpId && (
        <OpenShiftModal
          pumpId={pumpId}
          activeShifts={activeShifts}
          onClose={() => { setShowOpenModal(false); setClosedShiftPrefill(null) }}
          prefilledDuId={closedShiftPrefill?.duId}
          prefilledNozzleIds={closedShiftPrefill?.nozzleIds}
        />
      )}
      {showBackfillModal && pumpId && (
        <BackfillShiftModal
          pumpId={pumpId}
          onClose={() => setShowBackfillModal(false)}
        />
      )}
      {shiftToClose && (
        <CloseShiftModal
          shift={shiftToClose}
          onClose={() => setShiftToClose(null)}
          onOpenNextShift={canOpenShift ? (duId, nozzleIds) => {
            setShiftToClose(null)
            setClosedShiftPrefill({ duId, nozzleIds })
            setShowOpenModal(true)
          } : undefined}
        />
      )}
      {shiftToHandOver && (
        <CloseShiftModal
          shift={shiftToHandOver}
          onClose={() => setShiftToHandOver(null)}
          handoverActiveShifts={activeShifts}
        />
      )}
      {shiftForCredit && (
        <AddCreditEntryModal
          shift={shiftForCredit}
          onClose={() => setShiftIdForCredit(null)}
        />
      )}
    </div>
  )
}
