import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Landmark } from 'lucide-react'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { Settlement, SettlementPaymentType } from '../../api/paymentSettlementApi'
import { Reveal } from '../../components/Reveal'
import { EmptyState } from '../../components/EmptyState'
import { RefreshIndicator } from '../../components/RefreshIndicator'
import { canPerformAction } from '../../permissions/permissions'
import { useSettlementHistory } from './useSettlementHistory'
import { WalletCards } from './WalletCards'
import { SettlementRecordsTab } from './SettlementRecordsTab'
import { SettlementConfigTab } from './SettlementConfigTab'
import { RecordSettlementDialog } from './RecordSettlementDialog'
import { VoidSettlementDialog } from './VoidSettlementDialog'
import { PendingByAgeCard } from './PendingByAgeCard'

// ── Page ───────────────────────────────────────────────────────────────────

export default function PaymentSettlementsPage() {
  const { t } = useTranslation('settlements')
  const { user }                   = useAuthStore()
  const { selectedPumpId: pumpId } = usePumpStore()

  const canWrite     = canPerformAction('settlement:write', user?.role)
  const canConfigure = canPerformAction('settlement:configure', user?.role)
  const canVoid      = canPerformAction('settlement:void', user?.role)

  const [activeTab, setActiveTab]   = useState<'records' | 'config'>('records')
  const [recordType, setRecordType] = useState<SettlementPaymentType | null>(null)
  const [voidTarget, setVoidTarget] = useState<Settlement | null>(null)

  const history = useSettlementHistory(pumpId)
  const { data: wallet, isLoading: loadingWallet } = useQuery({
    queryKey: ['settlements-wallet', pumpId],
    queryFn:  () => paymentSettlementApi.getWallet(pumpId!),
    enabled:  !!pumpId,
  })

  const pendingByAge = useQuery({
    queryKey: ['settlements-pending-age', pumpId],
    queryFn:  () => paymentSettlementApi.getPendingByAge(pumpId!),
    enabled:  !!pumpId,
  })

  if (!pumpId) return <EmptyState icon="generic" title={t('page.noPump')} />

  return (
    <Reveal>
      <div className="ui-page ui-page--narrow">

        {/* ── Header ── */}
        <div className="ui-page-header mb-6">
          <div>
            <div className="flex items-center gap-2.5">
              <h1 className="ui-title-sm">{t('page.title')}</h1>
              <RefreshIndicator isFetching={history.fetchingList} dataUpdatedAt={history.listUpdatedAt ?? 0} />
            </div>
            <p className="ui-subtitle">
              {t('page.subtitle')}
            </p>
          </div>
          {canWrite && (
            <button
              onClick={() => setRecordType('UPI')}
              className="ui-btn ui-btn-primary"
            >
              <Landmark size={16} strokeWidth={2} />
              {t('page.record')}
            </button>
          )}
        </div>

        <WalletCards wallet={wallet} loadingWallet={loadingWallet} canWrite={canWrite} onRecord={setRecordType} />

        <PendingByAgeCard ranges={pendingByAge.data} isLoading={pendingByAge.isLoading} error={pendingByAge.error} />

        {/* ── Tabs ── */}
        <div className="flex gap-0 mb-5 border-b border-border">
          {(['records', 'config'] as const).map(tab => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`px-5 py-2.5 text-sm font-semibold border-b-2 transition-colors -mb-px ${
                activeTab === tab
                  ? 'border-primary text-primary'
                  : 'border-transparent text-ink-3 hover:text-ink'
              }`}
            >
              {tab === 'records' ? t('page.historyTab') : t('page.alertsTab')}
            </button>
          ))}
        </div>

        {activeTab === 'records' && (
          <SettlementRecordsTab history={history} canVoid={canVoid} onVoid={setVoidTarget} />
        )}

        {activeTab === 'config' && (
          <SettlementConfigTab pumpId={pumpId} canConfigure={canConfigure} />
        )}
      </div>

      {recordType && (
        <RecordSettlementDialog
          pumpId={pumpId}
          wallet={wallet}
          pendingRanges={pendingByAge.data ?? []}
          initialType={recordType}
          onClose={() => setRecordType(null)}
        />
      )}

      {voidTarget && (
        <VoidSettlementDialog pumpId={pumpId} voidTarget={voidTarget} onClose={() => setVoidTarget(null)} />
      )}
    </Reveal>
  )
}
