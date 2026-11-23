import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Reveal } from '../../components/Reveal'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { pumpApi } from '../../api/pumpApi'
import { DipPlTab } from './tabs/DipPlTab'
import { DiscrepancyTab } from './tabs/DiscrepancyTab'
import { ExpensesTab } from './tabs/ExpensesTab'
import { InterestAccrualTab } from './tabs/InterestAccrualTab'
import { InventoryLotsTab } from './tabs/InventoryLotsTab'
import { OperatorDutyTab } from './tabs/OperatorDutyTab'
import { ProfitLossTab } from './tabs/ProfitLossTab'
import { ShiftsTab } from './tabs/ShiftsTab'
import { fmtDateTime } from './reportFormat'

// ── Main Page ─────────────────────────────────────────────────────────────────

type Tab = 'pl' | 'duty' | 'discrepancy' | 'inventory' | 'dip' | 'shifts' | 'expenses' | 'interest'

const MANAGER_TABS: Tab[] = ['duty', 'discrepancy', 'shifts']

export default function ReportsPage() {
  const { t } = useTranslation('reports')
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isManager = user?.role === 'MANAGER'

  const [searchParams, setSearchParams] = useSearchParams()
  const tab = searchParams.get('tab')
  const setTab = (next: Tab) => {
    setSearchParams((params) => {
      const updated = new URLSearchParams(params)
      updated.set('tab', next)
      return updated
    }, { replace: true })
  }
  const { selectedPumpId } = usePumpStore()

  const pumpId = selectedPumpId

  const { data: pumps = [] } = useQuery({
    queryKey: ['myPumps'],
    queryFn: () => pumpApi.getMyPumps(),
  })

  const pumpName = pumpId ? (pumps.find(p => p.id === pumpId)?.name ?? '') : ''
  const userName = user?.fullName ?? ''

  const ALL_TABS: { id: Tab; label: string }[] = [
    { id: 'pl',          label: t('page.tabs.pl') },
    { id: 'duty',        label: t('page.tabs.duty') },
    { id: 'discrepancy', label: t('page.tabs.discrepancy') },
    { id: 'inventory',   label: t('page.tabs.inventory') },
    { id: 'dip',         label: t('page.tabs.dip') },
    { id: 'shifts',      label: t('page.tabs.shifts') },
    { id: 'expenses',    label: t('page.tabs.expenses') },
    { id: 'interest',    label: t('page.tabs.interest') },
  ]

  const TABS = isManager
    ? ALL_TABS.filter(item => MANAGER_TABS.includes(item.id))
    : ALL_TABS

  const activeTab = TABS.find(item => item.id === tab)?.id ?? TABS[0]?.id ?? 'duty'

  if (!pumpId && !isOwnerOrAdmin && !isManager) {
    return (
      <div className="ui-page ui-page--narrow">
        <div className="ui-alert ui-alert-warning text-sm">
          {t('page.notAssigned')}
        </div>
      </div>
    )
  }

  return (
    <div className="ui-page ui-page--narrow space-y-5">
      <Reveal delay={60}>
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">{t('page.kicker')}</p>
          <h1 className="ui-title-sm">{t('page.title')}</h1>
          <p className="ui-subtitle print:hidden">{t('page.subtitle')}</p>
          <p className="hidden print:block text-xs text-ink-3 mt-0.5">
            {pumpName && <><span className="font-medium">{pumpName}</span> · </>}
            {userName && <>{t('page.printedBy')} <span className="font-medium">{userName}</span> · </>}
            <span className="text-ink-3">{fmtDateTime(new Date().toISOString())}</span>
          </p>
        </div>
      </div>
      </Reveal>

      {!pumpId ? (
        <div className="ui-empty">
          {t('page.noPump')}
        </div>
      ) : (
        <div className="ui-card p-0 overflow-hidden">
          {/* Tabs — hidden when printing */}
          <div className="ui-tabbar print:hidden">
            {TABS.map((item) => (
              <button
                key={item.id}
                aria-pressed={activeTab === item.id}
                onClick={() => setTab(item.id)}
                className={`ui-tabbar__button ${
                  activeTab === item.id
                    ? 'ui-tabbar__button--active'
                    : ''
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>

          {/* Tab content */}
          <div key={activeTab} className="p-5 ui-tab-content">
            {activeTab === 'pl'          && <ProfitLossTab          pumpId={pumpId} />}
            {activeTab === 'duty'        && <OperatorDutyTab        pumpId={pumpId} />}
            {activeTab === 'discrepancy' && <DiscrepancyTab         pumpId={pumpId} />}
            {activeTab === 'inventory'   && <InventoryLotsTab       pumpId={pumpId} />}
            {activeTab === 'dip'         && <DipPlTab               pumpId={pumpId} />}
            {activeTab === 'shifts'      && <ShiftsTab              pumpId={pumpId} />}
            {activeTab === 'expenses'    && <ExpensesTab            pumpId={pumpId} />}
            {activeTab === 'interest'    && <InterestAccrualTab     pumpId={pumpId} />}
          </div>
        </div>
      )}

    </div>
  )
}
