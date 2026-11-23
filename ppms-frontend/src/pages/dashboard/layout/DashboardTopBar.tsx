import { Fuel } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { PumpSummary } from '../../../api/pumpApi'
import NotificationBell from '../../../components/NotificationBell'
import { CommandPaletteLauncher } from './CommandPaletteLauncher'
import { PumpSwitcher } from './PumpSwitcher'
import { UserProfileArea } from './UserProfileArea'

interface DashboardTopBarProps {
  /** Pumps the user may switch between; empty for staff, who work at one pump. */
  switchablePumps: PumpSummary[]
  pumpName: string | undefined
  /** The pump whose notifications the bell shows; null hides the bell. */
  notificationPumpId: number | null
}

/** The 56px bar: brand, pump, "Jump to…", notifications and the profile menu. Phones keep pump, bell and profile. */
export function DashboardTopBar({ switchablePumps, pumpName, notificationPumpId }: DashboardTopBarProps) {
  const { t } = useTranslation(['nav', 'common'])
  const canSwitchPump = switchablePumps.length > 0

  return (
    <header className="ui-dashboard-topbar print:hidden">
      <div className="ui-dashboard-topbar-left">
        <div className="ui-dashboard-brandmark" aria-hidden="true">
          <Fuel size={18} className="text-ink" strokeWidth={1.8} />
        </div>
        <span className="ui-dashboard-brandname">PPMS</span>
      </div>

      <div className="ui-dashboard-topbar-center">
        {canSwitchPump && <PumpSwitcher pumps={switchablePumps} />}
        <p className={`ui-dashboard-topbar-pump ${canSwitchPump ? 'ui-dashboard-topbar-pump--phone' : ''}`}>
          <span className="sr-only">{t('sidebar.currentPump')}: </span>
          {pumpName ?? t('common:notices.noPumpTitle')}
        </p>
      </div>

      <div className="ui-dashboard-topbar-right">
        <CommandPaletteLauncher />
        {notificationPumpId !== null && <NotificationBell pumpId={notificationPumpId} />}
        <UserProfileArea />
      </div>
    </header>
  )
}
