import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Fuel } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { pumpApi } from '../../api/pumpApi'
import NotificationBell from '../../components/NotificationBell'
import { ToastContainer } from '../../components/Toast'
import { DashboardSidebar } from './layout/DashboardSidebar'
import { DashboardBottomNav } from './layout/DashboardBottomNav'
import { PumpSwitcher } from './layout/PumpSwitcher'
import { LiveClock } from './layout/LiveClock'
import { RouteOutlet } from './layout/RouteOutlet'
import { SuspendedPumpNotice } from './layout/SuspendedPumpNotice'
import { UserProfileArea } from './layout/UserProfileArea'

export default function DashboardPage() {
  const { t } = useTranslation('nav')
  const location  = useLocation()
  const { user } = useAuthStore()

  const { data: pumps = [], isSuccess: pumpsLoaded } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps, staleTime: 5 * 60_000 })
  const sortedPumps = useMemo(
    () => [...pumps].sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()),
    [pumps]
  )
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isStaff = user != null && user.role !== 'OWNER' && user.role !== 'SUPER_ADMIN'
  // The pump list leaves out suspended pumps, so staff whose pump is missing from it are at a suspended pump.
  const assignedPumpSuspended = pumpsLoaded && isStaff && user.assignedPumpId != null
    && !pumps.some(pump => pump.id === user.assignedPumpId)

  const { selectedPumpId, suspendedPumpId, setSelectedPumpId } = usePumpStore()
  const selectedPumpSuspended = selectedPumpId !== null && selectedPumpId === suspendedPumpId
  const showSuspendedNotice = selectedPumpSuspended || assignedPumpSuspended
  const queryClient = useQueryClient()
  const [navOpen, setNavOpen] = useState(false)
  const moreButtonRef = useRef<HTMLButtonElement>(null)
  const closeNav = useCallback(() => {
    setNavOpen(false)
    moreButtonRef.current?.focus()
  }, [])

  // Initialise / validate the stored pump whenever the pump list loads.
  // If nothing is stored yet, or the stored pump no longer exists, default to the first pump.
  // A pump the server just reported as suspended stays selected so its notice is seen before switching.
  useEffect(() => {
    if (sortedPumps.length > 0 && !selectedPumpSuspended) {
      const valid = sortedPumps.find(p => p.id === selectedPumpId)
      if (!valid) setSelectedPumpId(sortedPumps[0].id)
    }
  }, [sortedPumps, selectedPumpId, selectedPumpSuspended, setSelectedPumpId])

  useEffect(() => {
    if (suspendedPumpId !== null) queryClient.invalidateQueries({ queryKey: ['myPumps'] })
  }, [suspendedPumpId, queryClient])

  // Move focus to main content on route change so screen readers announce the new page
  useEffect(() => {
    document.getElementById('main-content')?.focus()
  }, [location.pathname])

  return (
    <div className="ui-dashboard-shell h-dvh bg-bg flex flex-col overflow-hidden">

      {/* Skip link — visible only on keyboard focus */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:top-2 focus:left-2 focus:px-4 focus:py-2 focus:rounded-control focus:bg-surface focus:text-primary focus:font-medium focus:shadow-overlay"
      >
        {t('topbar.skipToContent')}
      </a>

      {/* ── Top header ─────────────────────────────────────────────────────────── */}
      <header className="ui-dashboard-topbar print:hidden">

        {/* ── Left: Logo + tagline ── */}
        <div className="ui-dashboard-topbar-left">
          <div className="ui-dashboard-brandmark">
            <Fuel size={20} className="text-ink" strokeWidth={1.8} />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-base font-extrabold text-surface tracking-wide">PPMS</span>
              <span className="text-xs text-on-dark-2 font-medium hidden sm:inline border border-white/25 px-2 py-0.5 rounded-badge">
                {t('topbar.tagline')}
              </span>
            </div>
            <p className="text-xs text-on-dark-2 italic hidden sm:block mt-0.5">
              {t('topbar.motto')}
            </p>
          </div>
        </div>

        {/* ── Center: Live date/time + pump selector ── */}
        <div className="ui-dashboard-topbar-center">
          <LiveClock />
          {isOwnerOrAdmin && <PumpSwitcher pumps={sortedPumps} />}
        </div>

        {/* ── Right: Notification bell + User info + profile menu ── */}
        <div className="ui-dashboard-topbar-right">
          {selectedPumpId && !showSuspendedNotice && <NotificationBell pumpId={selectedPumpId} />}
          <UserProfileArea />
        </div>
      </header>

      {/* ── Body ───────────────────────────────────────────────────────────────── */}
      <div className="flex flex-1 min-h-0" style={{ overflow: 'clip' }}>

        {/* ── Sidebar ── */}
        <DashboardSidebar
          pumpName={sortedPumps.find((pump) => pump.id === selectedPumpId)?.name}
          switchablePumps={isOwnerOrAdmin ? sortedPumps : []}
          drawerOpen={navOpen}
          onCloseDrawer={closeNav}
        />

        {/* ── Main content ── */}
        <main id="main-content" tabIndex={-1} className="flex-1 min-w-0 overflow-y-auto focus:outline-none">
          {showSuspendedNotice
            ? <SuspendedPumpNotice
                canSwitchPump={isOwnerOrAdmin && sortedPumps.some(p => p.id !== selectedPumpId)}
                isStaff={isStaff} />
            : <RouteOutlet />}
        </main>
      </div>

      <DashboardBottomNav drawerOpen={navOpen} onOpenDrawer={() => setNavOpen(true)} moreButtonRef={moreButtonRef} />

      <ToastContainer />

    </div>
  )
}
