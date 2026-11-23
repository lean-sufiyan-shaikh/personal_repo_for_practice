import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../store/authStore'
import { canAccessPage, canPerformAction } from '../../permissions/permissions'
import { usePumpStore } from '../../store/usePumpStore'
import { creditApi } from '../../api/creditApi'
import type { CreditClient } from '../../api/creditApi'
import { pumpApi } from '../../api/pumpApi'
import { Reveal } from '../../components/Reveal'
import { ClientDetail } from './ClientDetail'
import { LedgerTab } from './LedgerTab'
import { CreditClientsTab } from './CreditClientsTab'
import { MoneyText } from '../../components/AmountText'

const CREDIT_PATH = '/dashboard/credit'

// ─── Main Credit Page ────────────────────────────────────────────────────────

export default function CreditPage() {
  const { t } = useTranslation('credit')
  const { user } = useAuthStore()
  const isOwnerOrAdmin    = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const canSeeLedger      = canAccessPage('credit', user?.role)
  const canAdjustLimit    = canPerformAction('credit:adjust-limit', user?.role)
  const canManageClients  = canPerformAction('credit:manage-client', user?.role)

  const { selectedPumpId } = usePumpStore()
  const navigate = useNavigate()
  const location = useLocation()
  const { clientId: clientIdParam } = useParams()
  const selectedClientId = clientIdParam && /^\d+$/.test(clientIdParam) ? Number(clientIdParam) : null

  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab: 'ledger' | 'clients' =
    searchParams.get('tab') === 'clients' || !canSeeLedger ? 'clients' : 'ledger'
  const setActiveTab = (tab: 'ledger' | 'clients') => setSearchParams({ tab }, { replace: true })

  const openClient = (client: Pick<CreditClient, 'id'>) =>
    navigate(`${CREDIT_PATH}/${client.id}`, { state: { fromCreditList: true } })

  const backToList = () => {
    if ((location.state as { fromCreditList?: boolean } | null)?.fromCreditList) {
      navigate(-1)
    } else {
      navigate(`${CREDIT_PATH}?tab=ledger`, { replace: true })
    }
  }

  const pumpId = selectedPumpId

  const { data: clients = [], isLoading, error } = useQuery({
    queryKey: ['credit-ledger', pumpId],
    queryFn: () => creditApi.getLedgerSummary(pumpId!),
    enabled: !!pumpId && canSeeLedger,
  })

  // Fetch the full client list for reliable parentClientId / isParent structure.
  // The ledger endpoint may not populate parentClientId, so we use this for grouping.
  const { data: allCreditClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn:  () => pumpApi.getCreditClientAccounts(pumpId!),
    enabled:  !!pumpId,
  })

  if (!isOwnerOrAdmin && !pumpId) {
    return (
      <div className="ui-page ui-page--narrow">
        <div className="ui-alert ui-alert-warning text-sm">
          {t('page.notAssigned')}
        </div>
      </div>
    )
  }

  if (selectedClientId !== null && pumpId) {
    return (
      <div className="ui-page ui-page--narrow space-y-5">
        <ClientDetail
          clientId={selectedClientId}
          pumpId={pumpId}
          onBack={backToList}
        />
      </div>
    )
  }

  // Only sum root accounts — parent outstandingBalance already includes sub-account balances,
  // so adding sub-accounts separately would double-count them.
  const rootClients = clients.filter(c => c.parentClientId === null)
  const totalOutstanding = rootClients.reduce((sum, c) => sum + c.outstandingBalance, 0)
  const rootClientCount  = allCreditClients.length > 0
    ? allCreditClients.filter(c => !c.parentClientId).length
    : clients.filter(c => !c.parentClientId).length

  return (
    <div className="ui-page ui-page--narrow space-y-5">
      {/* Page header */}
      <Reveal delay={60}>
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">{canSeeLedger ? t('page.kickerLedger') : t('page.kickerClients')}</p>
          <h1 className="ui-title-sm">{canSeeLedger ? t('page.titleLedger') : t('page.titleClients')}</h1>
          <p className="ui-subtitle">
            {canSeeLedger ? t('page.subtitleLedger') : t('page.subtitleClients')}
          </p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.accounts')}</span>
            <span className="ui-section-meta-value">{rootClientCount}</span>
          </div>
          {canSeeLedger && (
            <div className="ui-section-meta-pill">
              <span className="ui-section-meta-label">{t('summaryCards.outstanding')}</span>
              <MoneyText value={totalOutstanding} className="ui-section-meta-value" />
            </div>
          )}
        </div>
      </div>
      </Reveal>

      {/* Tab bar — only shown for roles with ledger access */}
      {canSeeLedger && (
        <Reveal delay={100}>
        <div className="ui-tabbar w-fit">
          {(['clients', 'ledger'] as const).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`ui-tabbar__button ${activeTab === tab ? 'ui-tabbar__button--active' : ''}`}
            >
              {t(`page.tabs.${tab}`)}
            </button>
          ))}
        </div>
        </Reveal>
      )}

      {/* ── Ledger tab ── */}
      {activeTab === 'ledger' && (
        <LedgerTab
          pumpId={pumpId}
          clients={clients}
          isLoading={isLoading}
          hasError={!!error}
          canAdjustLimit={canAdjustLimit}
          canIssueInvoices={canPerformAction('credit:issue-invoice', user?.role)}
          onSelectClient={openClient}
        />
      )}

      {/* ── Clients tab ── */}
      {activeTab === 'clients' && (
        <Reveal delay={120}>
        <div className="ui-card">
          {pumpId ? (
            <CreditClientsTab pumpId={pumpId} canManageClients={canManageClients}
              canManagePortalPins={canPerformAction('credit:manage-portal-pin', user?.role)} />
          ) : (
            <div className="ui-empty">{t('page.noPump')}</div>
          )}
        </div>
        </Reveal>
      )}
    </div>
  )
}
