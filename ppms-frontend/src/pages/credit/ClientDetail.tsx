import { useState } from 'react'
import { withToggled } from '../../utils/setToggle'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { creditApi } from '../../api/creditApi'
import type { CreditTransaction, CreditExtension } from '../../api/creditApi'
import { Pagination } from '../../components/Pagination'
import { canPerformAction } from '../../permissions/permissions'
import { SkeletonRows } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { IconButton } from '../../components/IconButton'
import { GrantExtensionModal } from './modals/GrantExtensionModal'
import { buildWeekGroups } from './ledgerGroups'
import { LedgerWeekGroups } from './LedgerWeekGroups'
import type { LedgerRowActions } from './LedgerWeekGroups'
import { ReassignCreditEntryModal } from './modals/ReassignCreditEntryModal'
import { RecordPaymentModal } from './modals/RecordPaymentModal'
import { SetInterestSettingsModal } from './modals/SetInterestSettingsModal'
import { useClientLedger } from './useClientLedger'
import { useLedgerFilters } from './useLedgerFilters'
import { LedgerFilterBar } from './LedgerFilterBar'
import { ClientSummaryCards } from './ClientSummaryCards'

// ─── Client Detail (transaction history) ────────────────────────────────────

interface ClientDetailProps {
  clientId: number
  pumpId: number
  isOwnerOrAdmin: boolean
  onBack: () => void
}

export function ClientDetail({ clientId, pumpId, isOwnerOrAdmin, onBack }: ClientDetailProps) {
  const [showPaymentModal, setShowPaymentModal] = useState(false)
  const [showInterestSettingsModal, setShowInterestSettingsModal] = useState(false)
  const [showExtensionModal, setShowExtensionModal] = useState(false)
  const [applyInterestFeedback, setApplyInterestFeedback] = useState<string | null>(null)
  const [confirmDeleteInterest, setConfirmDeleteInterest] = useState<{ chargeId: number; sourceClientId: number } | null>(null)
  const [reassignEntryId,         setReassignEntryId]         = useState<number | null>(null)
  // Track which client the entry being reassigned belongs to (may be a child in parent view)
  const [reassignSourceClientId,  setReassignSourceClientId]  = useState<number>(clientId)
  const [reassignType,            setReassignType]            = useState<'SALE' | 'PAYMENT'>('SALE')
  // Tracks which sections are expanded in the parent ledger view.
  // 'own' (parent's own transactions) starts expanded; sub-account sections start collapsed.
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['own']))
  const toggleSection = (key: string) =>
    setExpandedSections(prev => withToggled(prev, key))
  const qc = useQueryClient()

  const { user } = useAuthStore()
  const { addToast } = useToastStore()
  const role = user?.role
  const canApprovePayments = canPerformAction('credit:approve-payment', role)
  const [txPage, setTxPage] = useState(0)
  const [txPageSize, setTxPageSize] = useState(10)

  const {
    allClients, client, childClients, activeExtensions, ownTransactions, isLoading,
    outstandingInterest, totalInterestRecovered, childTxQueries, childTransactionsByIndex,
    pendingPaymentRecorders, transactions,
  } = useClientLedger(pumpId, clientId, canApprovePayments)

  const invalidateLedger = () => {
    qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId] })
    qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
    qc.invalidateQueries({ queryKey: ['credit-pending-payments', pumpId] })
  }

  const applyInterestMutation = useMutation({
    mutationFn: () => creditApi.applyInterest(pumpId, clientId),
    onSuccess: (result) => {
      invalidateLedger()
      if (result.applied) {
        setApplyInterestFeedback(`₹${result.amount?.toFixed(2)} interest applied for ${result.days} days (${result.periodFrom} – ${result.periodTo})`)
      } else {
        setApplyInterestFeedback(result.reason ?? 'No interest applicable')
      }
    },
    onError: (err) => setApplyInterestFeedback(parseApiError(err, 'Failed to apply interest')),
  })

  const deleteInterestMutation = useMutation({
    mutationFn: ({ chargeId, sourceClientId }: { chargeId: number; sourceClientId: number }) =>
      creditApi.deleteInterestCharge(pumpId, sourceClientId, chargeId),
    onSuccess: () => {
      setConfirmDeleteInterest(null)
      invalidateLedger()
    },
    onError: (err) => {
      setConfirmDeleteInterest(null)
      addToast(parseApiError(err, 'Failed to remove interest charge'), 'error')
    },
  })

  const approvePaymentMutation = useMutation({
    mutationFn: ({ paymentId, status }: { paymentId: number; status: 'APPROVED' | 'REJECTED' }) =>
      creditApi.approvePayment(pumpId, paymentId, status),
    onSuccess: (_payment, { status }) => {
      invalidateLedger()
      addToast(status === 'APPROVED' ? 'Payment approved' : 'Payment rejected', 'success')
    },
    onError: (err) => addToast(parseApiError(err, 'Failed to update payment'), 'error'),
  })

  const ledgerFilters = useLedgerFilters(transactions, ownTransactions, childTransactionsByIndex, txPage, txPageSize)
  const { filtered, pagedFiltered, weekGroups, childFilteredTxs, filteredOwn } = ledgerFilters

  const formatAmount = (n: number) =>
    n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  // The server balance already counts sub-accounts for a parent; pending payments are not yet in it.
  const pendingPaymentTotal = transactions
    .filter(tx => tx.type === 'PAYMENT' && tx.status === 'PENDING_APPROVAL')
    .reduce((sum, tx) => sum + tx.amount, 0)
  const effectiveOutstandingBalance = client
    ? Math.max(0, Math.round((client.outstandingBalance - pendingPaymentTotal) * 100) / 100)
    : 0
  const canRecordPayment = !!client && canPerformAction('credit:record-payment', role) && (
    effectiveOutstandingBalance > 0 ||
    (client.isParent && childClients.length > 0)
  )

  const rowActions: LedgerRowActions = {
    isOwnerOrAdmin,
    canApprovePayments,
    currentUserId: user?.userId,
    pendingPaymentRecorders,
    approvePayment: (paymentId, status) => approvePaymentMutation.mutate({ paymentId, status }),
    isApproving: approvePaymentMutation.isPending,
    onMove: (entryId, sourceClientId, type) => {
      setReassignEntryId(entryId)
      setReassignSourceClientId(sourceClientId)
      setReassignType(type)
    },
    confirmDeleteInterest,
    setConfirmDeleteInterest,
    deleteInterest: (chargeId, sourceClientId) => deleteInterestMutation.mutate({ chargeId, sourceClientId }),
    isDeletingInterest: deleteInterestMutation.isPending,
  }

  if (!client) {
    return <div className="px-5 py-4"><SkeletonRows count={4} /></div>
  }

  return (
    <div>
      {showPaymentModal && (
        <RecordPaymentModal
          client={client}
          pumpId={pumpId}
          outstandingBalanceOverride={effectiveOutstandingBalance}
          subClients={allClients.filter(c => c.parentClientId === clientId)}
          onClose={() => setShowPaymentModal(false)}
        />
      )}
      {reassignEntryId !== null && isOwnerOrAdmin && (
        <ReassignCreditEntryModal
          entryId={reassignEntryId}
          currentClientId={reassignSourceClientId}
          pumpId={pumpId}
          transactionType={reassignType}
          onClose={() => { setReassignEntryId(null) }}
        />
      )}
      {showInterestSettingsModal && (
        <SetInterestSettingsModal
          client={client}
          pumpId={pumpId}
          onClose={() => setShowInterestSettingsModal(false)}
        />
      )}
      {showExtensionModal && isOwnerOrAdmin && (
        <GrantExtensionModal
          client={client}
          pumpId={pumpId}
          onClose={() => setShowExtensionModal(false)}
        />
      )}

      {/* Header */}
      <div className="p-6 pb-4">
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 mb-4"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
          Back to all clients
        </button>

        <div className="flex items-start justify-between flex-wrap gap-3">
          <div>
            <h2 className="text-xl font-bold text-slate-800">{client.name}</h2>
            <p className="text-sm text-slate-500 mt-0.5">{client.phone}</p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {isOwnerOrAdmin && (
              <button
                onClick={() => setShowInterestSettingsModal(true)}
                className="ui-btn ui-btn-secondary text-purple-700 border-purple-200 hover:bg-purple-50"
              >
                Interest Settings
              </button>
            )}
            {canPerformAction('credit:manage-interest', role) && client.monthlyInterestRate > 0 && client.outstandingBalance > 0 && (
              <button
                onClick={() => { setApplyInterestFeedback(null); applyInterestMutation.mutate() }}
                disabled={applyInterestMutation.isPending}
                className="ui-btn text-white bg-purple-600 hover:bg-purple-700 disabled:opacity-50"
              >
                {applyInterestMutation.isPending ? 'Applying…' : 'Apply Interest'}
              </button>
            )}
            {isOwnerOrAdmin && (
              <button
                onClick={() => setShowExtensionModal(true)}
                className="ui-btn ui-btn-secondary text-blue-700 border-blue-200 hover:bg-blue-50"
              >
                Grant Extension
              </button>
            )}
            {canRecordPayment && (
              <button
                onClick={() => setShowPaymentModal(true)}
                className="ui-btn text-white bg-green-600 hover:bg-green-700"
              >
                Record Payment
              </button>
            )}
          </div>
        </div>

        {applyInterestFeedback && (
          <div className="mt-3 ui-alert text-sm bg-purple-50 border-purple-200 text-purple-800 flex items-center justify-between">
            <span>{applyInterestFeedback}</span>
            <IconButton onClick={() => setApplyInterestFeedback(null)} className="ml-2 text-purple-400 hover:text-purple-600"
              label="Dismiss message"
              icon={'×'}
            />
          </div>
        )}
        {isOwnerOrAdmin && client.monthlyInterestRate === 0 && client.outstandingBalance > 0 && (
          <div className="mt-3 ui-card-plain px-4 py-2.5 text-sm text-slate-500 flex items-center gap-2">
            <span>No interest rate configured.</span>
            <button
              onClick={() => setShowInterestSettingsModal(true)}
              className="text-purple-600 font-medium hover:text-purple-800 underline-offset-2 hover:underline"
            >
              Set interest rate
            </button>
          </div>
        )}

        {/* Active extension banners */}
        {activeExtensions.map((ext: CreditExtension) => (
          <div key={ext.id} className="mt-3 ui-alert text-sm bg-blue-50 border-blue-200 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="font-medium text-blue-800">
                {ext.extensionType === 'AMOUNT_EXTENSION' && `+₹${ext.extensionAmount?.toLocaleString('en-IN')} credit headroom`}
                {ext.extensionType === 'BILLING_CYCLE_EXTENSION' && 'Billing cycle overdue block deferred'}
                {ext.extensionType === 'OVERDUE_BLOCK_WAIVER' && 'Overdue block waived'}
              </span>
              <span className="text-xs text-blue-500">expires {ext.expiryDate}</span>
            </div>
            <span className="text-xs text-blue-400 italic truncate max-w-xs">{ext.reason}</span>
          </div>
        ))}
      </div>

      <ClientSummaryCards
        client={client}
        hasSubAccounts={client.isParent && childClients.length > 0}
        outstandingBalance={effectiveOutstandingBalance}
        outstandingInterest={outstandingInterest}
        totalInterestRecovered={totalInterestRecovered}
        transactionCount={transactions.length}
        isOwnerOrAdmin={isOwnerOrAdmin}
        onOpenInterestSettings={() => setShowInterestSettingsModal(true)}
      />

      {/* Transaction ledger */}
      <div className="px-6">
        <LedgerFilterBar filters={ledgerFilters} />

        {isLoading ? (
          <div className="px-5 py-4"><SkeletonRows count={5} /></div>
        ) : client.isParent ? (
          /* ── Parent view: parent's own transactions first, then sub-accounts ── */
          <div className="space-y-8">
            {/* Parent's own transactions — expanded by default */}
            <div>
              <button
                type="button"
                onClick={() => toggleSection('own')}
                className="w-full flex items-center justify-between mb-3 pb-2 border-b border-slate-100 hover:bg-slate-50/50 rounded px-1 -mx-1 transition-colors"
              >
                <div className="flex items-center gap-2.5">
                  <span className="w-8 h-8 rounded-full bg-slate-200 text-slate-600 text-xs font-bold flex items-center justify-center">
                    {client.name.charAt(0).toUpperCase()}
                  </span>
                  <div className="text-left">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-slate-700">{client.name}</span>
                      <span className="text-xs bg-slate-100 text-slate-500 px-1.5 py-0.5 rounded font-medium">own transactions</span>
                    </div>
                    <p className="text-xs text-slate-500">{client.phone}</p>
                  </div>
                </div>
                <svg
                  className={`w-4 h-4 text-slate-400 transition-transform ${expandedSections.has('own') ? 'rotate-180' : ''}`}
                  fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                </svg>
              </button>
              {expandedSections.has('own') && (
                filteredOwn.length === 0 ? (
                  <div className="ui-empty py-4">
                    {ownTransactions.length === 0 ? 'No own transactions yet.' : 'No transactions match this filter.'}
                  </div>
                ) : (
                  <div className="space-y-3">
                    <LedgerWeekGroups groups={buildWeekGroups(filteredOwn as CreditTransaction[])} srcClientId={clientId} actions={rowActions} />
                  </div>
                )
              )}
            </div>

            {/* Sub-account sections — collapsed by default */}
            {childClients.map((child, i) => {
              const sectionKey       = String(child.id)
              const isExpanded       = expandedSections.has(sectionKey)
              const childFiltered    = childFilteredTxs[i]
              const childGroups      = buildWeekGroups(childFiltered)
              const childLedger      = allClients.find(c => c.id === child.id)
              const childOutstanding = childLedger?.outstandingBalance ?? 0
              return (
                <div key={child.id}>
                  <button
                    type="button"
                    onClick={() => toggleSection(sectionKey)}
                    className="w-full flex items-center justify-between mb-3 pb-2 border-b border-slate-100 hover:bg-slate-50/50 rounded px-1 -mx-1 transition-colors"
                  >
                    <div className="flex items-center gap-2.5">
                      <span className="w-8 h-8 rounded-full bg-blue-100 text-blue-600 text-xs font-bold flex items-center justify-center">
                        {child.name.charAt(0).toUpperCase()}
                      </span>
                      <div className="text-left">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-semibold text-slate-700">{child.name}</span>
                          <span className="text-xs bg-blue-100 text-blue-600 px-1.5 py-0.5 rounded font-medium">sub-account</span>
                        </div>
                        <p className="text-xs text-slate-500">{child.phone}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-4">
                      <div className="text-right">
                        <p className="text-xs text-slate-500">Outstanding</p>
                        <p className={`text-sm font-bold ${childOutstanding > 0 ? 'text-amber-600' : 'text-green-600'}`}>
                          ₹{formatAmount(childOutstanding)}
                        </p>
                      </div>
                      <svg
                        className={`w-4 h-4 text-slate-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
                        fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                      </svg>
                    </div>
                  </button>
                  {isExpanded && (
                    childTxQueries[i]?.isLoading ? (
                      <div className="px-4 py-3"><SkeletonRows count={3} /></div>
                    ) : childFiltered.length === 0 ? (
                      <EmptyState
                        icon="transactions"
                        title={(childTxQueries[i]?.data ?? []).length === 0 ? 'No transactions yet' : 'No transactions match this filter'}
                      />
                    ) : (
                      <div className="space-y-3"><LedgerWeekGroups groups={childGroups} srcClientId={child.id} actions={rowActions} /></div>
                    )
                  )}
                </div>
              )
            })}
          </div>
        ) : filtered.length === 0 ? (
          <div className="ui-empty py-8">
            {transactions.length === 0 ? 'No transactions yet.' : 'No transactions match this filter.'}
          </div>
        ) : (
          <>
            <div className="space-y-4"><LedgerWeekGroups groups={weekGroups} srcClientId={clientId} actions={rowActions} /></div>
            <Pagination
              data={pagedFiltered}
              onPageChange={setTxPage}
              onPageSizeChange={size => { setTxPageSize(size); setTxPage(0) }}
            />
          </>
        )}
      </div>
    </div>
  )
}
