import type { CreditTransaction } from '../../api/creditApi'
import { formatIstDate } from '../../utils/date'

const formatDate = (iso: string) =>
  formatIstDate(iso, { day: '2-digit', month: 'short' })

const formatAmount = (n: number) =>
  n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export interface LedgerRowActions {
  isOwnerOrAdmin: boolean
  canApprovePayments: boolean
  currentUserId: number | undefined
  pendingPaymentRecorders: Map<number, number>
  approvePayment: (paymentId: number, status: 'APPROVED' | 'REJECTED') => void
  isApproving: boolean
  onMove: (entryId: number, sourceClientId: number, type: 'SALE' | 'PAYMENT') => void
  confirmDeleteInterest: { chargeId: number; sourceClientId: number } | null
  setConfirmDeleteInterest: (target: { chargeId: number; sourceClientId: number } | null) => void
  deleteInterest: (chargeId: number, sourceClientId: number) => void
  isDeletingInterest: boolean
}

interface LedgerWeekGroupsProps {
  groups: Array<{ label: string; items: CreditTransaction[] }>
  srcClientId: number
  actions: LedgerRowActions
}

// srcClientId is passed explicitly so the Move action knows which client owns the entry.
export function LedgerWeekGroups({ groups, srcClientId, actions }: LedgerWeekGroupsProps) {
  const {
    isOwnerOrAdmin, canApprovePayments, currentUserId, pendingPaymentRecorders,
    approvePayment, isApproving, onMove,
    confirmDeleteInterest, setConfirmDeleteInterest, deleteInterest, isDeletingInterest,
  } = actions

  return groups.map(({ label, items }) => {
    const weekSales    = items.filter(t => t.countedInBalance && t.type === 'SALE').reduce((s, t) => s + t.amount, 0)
    const weekPayments = items.filter(t => t.countedInBalance && t.type === 'PAYMENT').reduce((s, t) => s + t.amount, 0)
    const weekInterest = items.filter(t => t.countedInBalance && t.type === 'INTEREST').reduce((s, t) => s + t.amount, 0)
    return (
      <div key={label} className="ui-card p-0 overflow-hidden">
        <div className="ui-toolbar">
          <span className="ui-toolbar-title">{label}</span>
          <div className="ui-toolbar-actions text-xs">
            {weekSales    > 0 && <span className="text-orange-600">+₹{formatAmount(weekSales)} sales</span>}
            {weekInterest > 0 && <span className="text-purple-600">+₹{formatAmount(weekInterest)} interest</span>}
            {weekPayments > 0 && <span className="text-green-600">−₹{formatAmount(weekPayments)} paid</span>}
          </div>
        </div>
        <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <tbody className="divide-y divide-slate-50">
            {items.map((tx, idx) => (
              <tr key={`${tx.type}-${tx.referenceId}-${idx}`} className="hover:bg-slate-50">
                <td className="px-4 py-2.5 text-slate-500 text-xs whitespace-nowrap w-20">
                  {formatDate(tx.occurredAt)}
                </td>
                <td className="px-2 py-2.5 w-24">
                  <span className={`inline-flex text-xs font-medium px-2 py-0.5 rounded-full ${
                    tx.type === 'SALE' ? 'bg-orange-100 text-orange-700'
                    : tx.type === 'INTEREST' ? 'bg-purple-100 text-purple-700'
                    : 'bg-green-100 text-green-700'
                  }`}>
                    {tx.type === 'SALE' ? '↑ Sale' : tx.type === 'INTEREST' ? '% Interest' : '↓ Paid'}
                  </span>
                </td>
                <td className="px-2 py-2.5 text-xs max-w-[160px]">
                  <span className="text-slate-500 truncate block">{tx.reference || tx.detail || '—'}</span>
                  {!tx.countedInBalance && (
                    <span className="text-[10px] font-semibold text-red-500">{tx.status} · not in balance</span>
                  )}
                </td>
                <td className={`px-2 py-2.5 text-right text-xs font-semibold ${
                  tx.type === 'PAYMENT' ? 'text-green-600' : tx.type === 'INTEREST' ? 'text-purple-600' : 'text-orange-600'
                }`}>
                  {tx.type === 'PAYMENT' ? '−' : '+'}₹{formatAmount(tx.amount)}
                </td>
                <td className={`px-4 py-2.5 text-right text-xs font-bold w-28 ${
                  tx.runningBalance > 0 ? 'text-slate-700' : 'text-green-600'
                }`}>
                  ₹{formatAmount(tx.runningBalance)}
                </td>
                <td className="px-3 py-2.5 w-28 text-right">
                  {canApprovePayments && tx.type === 'PAYMENT' && tx.status === 'PENDING_APPROVAL'
                    && pendingPaymentRecorders.has(tx.referenceId)
                    && pendingPaymentRecorders.get(tx.referenceId) !== currentUserId && (
                    <span className="inline-flex items-center gap-1 mr-2">
                      <button
                        type="button"
                        onClick={() => approvePayment(tx.referenceId, 'APPROVED')}
                        disabled={isApproving}
                        className="ui-btn ui-btn-primary min-h-0 px-2 py-0.5 text-xs disabled:opacity-50"
                      >
                        Approve
                      </button>
                      <button
                        type="button"
                        onClick={() => approvePayment(tx.referenceId, 'REJECTED')}
                        disabled={isApproving}
                        className="ui-btn ui-btn-danger min-h-0 px-2 py-0.5 text-xs disabled:opacity-50"
                      >
                        Reject
                      </button>
                    </span>
                  )}
                  {isOwnerOrAdmin && (tx.type === 'SALE' || tx.type === 'PAYMENT') && (
                    <button
                      onClick={() => onMove(tx.referenceId, srcClientId, tx.type === 'PAYMENT' ? 'PAYMENT' : 'SALE')}
                      className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-blue-400 hover:text-blue-600"
                      title={`Move this ${tx.type === 'PAYMENT' ? 'payment' : 'entry'} within the account group`}
                    >
                      Move
                    </button>
                  )}
                  {isOwnerOrAdmin && tx.type === 'INTEREST' && (
                    confirmDeleteInterest?.chargeId === tx.referenceId ? (
                      <span className="inline-flex items-center gap-1">
                        <button onClick={() => deleteInterest(tx.referenceId, srcClientId)} disabled={isDeletingInterest}
                          className="ui-btn ui-btn-danger min-h-0 px-2 py-0.5 text-xs disabled:opacity-50">
                          {isDeletingInterest ? '…' : 'Yes'}
                        </button>
                        <button onClick={() => setConfirmDeleteInterest(null)}
                          className="ui-btn ui-btn-secondary min-h-0 px-2 py-0.5 text-xs">
                          No
                        </button>
                      </span>
                    ) : (
                      <button onClick={() => setConfirmDeleteInterest({ chargeId: tx.referenceId, sourceClientId: srcClientId })}
                        className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-red-400 hover:text-red-600"
                        title="Remove this interest charge">
                        Remove
                      </button>
                    )
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>
    )
  })
}
