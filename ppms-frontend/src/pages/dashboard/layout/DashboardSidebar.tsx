import { Link, useLocation } from 'react-router-dom'
import { useAuthStore } from '../../../store/authStore'
import { canAccessPage } from '../../../permissions/permissions'
import { ALL_NAV_ITEMS } from '../navItems'

export function DashboardSidebar({ pumpName }: { pumpName: string | undefined }) {
  const location = useLocation()
  const { user } = useAuthStore()

  // Filter nav items to only those the logged-in role is allowed to access
  const NAV_ITEMS = ALL_NAV_ITEMS.filter(item => canAccessPage(item.page, user?.role))

  return (
    <nav aria-label="Main" className="ui-dashboard-sidebar print:hidden">
      <div className="ui-dashboard-sidebar-head">
        <p className="ui-dashboard-sidebar-title">Navigation</p>
        <p className="ui-dashboard-sidebar-subtitle">
          {user?.role?.replace('_', ' ')} workspace
        </p>
      </div>
      <div className="px-3 py-3 flex-1">
        {NAV_ITEMS.map((item) => {
          const isActive =
            location.pathname === item.path ||
            (item.path !== '/dashboard' && location.pathname.startsWith(item.path))
          return (
            <Link
              key={item.path}
              to={item.path}
              aria-current={isActive ? 'page' : undefined}
              className={`ui-dashboard-navlink ${
                isActive
                  ? 'ui-dashboard-navlink--active'
                  : ''
              }`}
            >
              <div className={`w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 ${item.bg}`}>
                <item.icon size={14} strokeWidth={2} className={item.color} />
              </div>
              <span className="truncate">{item.label}</span>
            </Link>
          )
        })}
      </div>
      <div className="ui-dashboard-sidebar-foot">
        <p className="ui-dashboard-sidebar-foot-label">Current pump</p>
        <p className="ui-dashboard-sidebar-foot-value">
          {pumpName ?? 'No pump selected'}
        </p>
      </div>
    </nav>
  )
}
