import { useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { PumpSummary } from '../../../api/pumpApi'
import { IconButton } from '../../../components/IconButton'
import { useEscapeKey } from '../../../hooks/useEscapeKey'
import { useAuthStore } from '../../../store/authStore'
import { canAccessPage } from '../../../permissions/permissions'
import { roleLabel } from '../../../constants/labels'
import { ALL_NAV_ITEMS, isNavPathActive, navItemLabel } from '../navItems'
import { PumpSwitcher } from './PumpSwitcher'

export const DASHBOARD_NAV_ID = 'dashboard-navigation'

interface DashboardSidebarProps {
  pumpName: string | undefined
  /** Pumps the user may switch between; shown inside the drawer on phones, where the top bar has no room. */
  switchablePumps: PumpSummary[]
  /** On phones the sidebar is a drawer; on wider screens it is always visible and this is ignored. */
  drawerOpen: boolean
  onCloseDrawer: () => void
}

export function DashboardSidebar({ pumpName, switchablePumps, drawerOpen, onCloseDrawer }: DashboardSidebarProps) {
  const { t } = useTranslation('nav')
  const location = useLocation()
  const { user } = useAuthStore()
  const navRef = useRef<HTMLElement>(null)

  const navItems = ALL_NAV_ITEMS.filter(item => canAccessPage(item.page, user?.role))

  useEscapeKey(onCloseDrawer, drawerOpen)

  useEffect(() => {
    if (drawerOpen) navRef.current?.querySelector<HTMLElement>('a[aria-current="page"], a')?.focus()
  }, [drawerOpen])

  return (
    <>
      {drawerOpen && <div className="ui-dashboard-nav-backdrop print:hidden" onClick={onCloseDrawer} aria-hidden="true" />}
      <nav
        ref={navRef}
        id={DASHBOARD_NAV_ID}
        aria-label={t('sidebar.label')}
        className={`ui-dashboard-sidebar print:hidden ${drawerOpen ? 'ui-dashboard-sidebar--open' : ''}`}
      >
        <div className="ui-dashboard-sidebar-head">
          <div>
            <p className="ui-dashboard-sidebar-title">{t('sidebar.title')}</p>
            <p className="ui-dashboard-sidebar-subtitle">{t('sidebar.workspace', { role: roleLabel(user?.role) })}</p>
          </div>
          <IconButton label={t('sidebar.closeMenu')} onClick={onCloseDrawer} className="ui-btn ui-btn-ghost ui-dashboard-drawer-close"
            icon={<X size={18} strokeWidth={2} />} />
        </div>
        <div className="ui-dashboard-sidebar-pumps">
          <PumpSwitcher pumps={switchablePumps} variant="drawer" />
        </div>
        <div className="px-3 py-3 flex-1">
          {navItems.map((item) => {
            const isActive = isNavPathActive(item.path, location.pathname)
            return (
              <Link
                key={item.path}
                to={item.path}
                onClick={onCloseDrawer}
                aria-current={isActive ? 'page' : undefined}
                className={`ui-dashboard-navlink ${isActive ? 'ui-dashboard-navlink--active' : ''}`}
              >
                <div className={`w-7 h-7 rounded-control flex items-center justify-center flex-shrink-0 ${item.bg}`}>
                  <item.icon size={14} strokeWidth={2} className={item.color} />
                </div>
                <span className="truncate">{navItemLabel(item.page)}</span>
              </Link>
            )
          })}
        </div>
        <div className="ui-dashboard-sidebar-foot">
          <p className="ui-dashboard-sidebar-foot-label">{t('sidebar.currentPump')}</p>
          <p className="ui-dashboard-sidebar-foot-value">
            {pumpName ?? t('sidebar.noPump')}
          </p>
        </div>
      </nav>
    </>
  )
}
