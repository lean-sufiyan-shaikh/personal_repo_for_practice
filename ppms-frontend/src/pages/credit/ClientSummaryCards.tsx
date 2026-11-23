import type { CreditClient } from '../../api/creditApi'
import { onActivateKey } from '../../utils/keyboard'
import { formatDecimal } from '../../utils/format'
import { MoneyText } from '../../components/AmountText'

interface ClientSummaryCardsProps {
  client: CreditClient
  hasSubAccounts: boolean
  outstandingBalance: number
  /** Payments recorded but not yet approved; they are not deducted from the outstanding balance. */
  pendingApprovalTotal: number
  outstandingInterest: number
  totalInterestRecovered: number
  transactionCount: number
  canManageInterest: boolean
  onOpenInterestSettings: () => void
}

export function ClientSummaryCards({
  client, hasSubAccounts, outstandingBalance, pendingApprovalTotal, outstandingInterest, totalInterestRecovered,
  transactionCount, canManageInterest, onOpenInterestSettings,
}: ClientSummaryCardsProps) {
  return (
    <div className="px-6 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
      <div className="ui-card">
        <p className="text-xs text-ink-3 uppercase tracking-wide mb-1">
          Outstanding{hasSubAccounts ? ' (combined)' : ''}
        </p>
        <p className={`text-lg font-bold ${outstandingBalance > 0 ? 'text-warn' : 'text-ok'}`}>
          <MoneyText value={outstandingBalance} />
        </p>
        {pendingApprovalTotal > 0 && (
          <p className="text-xs font-medium text-warn mt-0.5">₹{formatDecimal(pendingApprovalTotal)} pending approval</p>
        )}
        {hasSubAccounts && (
          <p className="text-xs text-ink-3 mt-0.5">parent + all sub-accounts</p>
        )}
      </div>
      <div className={`ui-card ${outstandingInterest > 0 ? 'border-info/30 bg-info-bg' : ''}`}>
        <p className="text-xs text-ink-3 uppercase tracking-wide mb-1">Interest Due</p>
        <p className={`text-lg font-bold ${outstandingInterest > 0 ? 'text-info' : 'text-ink-3'}`}>
          <MoneyText value={outstandingInterest} />
        </p>
        <p className="text-xs text-ink-3 mt-0.5">of outstanding balance</p>
      </div>
      <div className="ui-card">
        <p className="text-xs text-ink-3 uppercase tracking-wide mb-1">Interest Recovered</p>
        <p className={`text-lg font-bold ${totalInterestRecovered > 0 ? 'text-ok' : 'text-ink-3'}`}>
          <MoneyText value={totalInterestRecovered} />
        </p>
        {hasSubAccounts && (
          <p className="text-xs text-ink-3 mt-0.5">parent + all sub-accounts</p>
        )}
      </div>
      <div
        className={`ui-card ${canManageInterest ? 'cursor-pointer hover:border-info hover:bg-info-bg transition-colors' : 'border-border'}`}
        onClick={canManageInterest ? onOpenInterestSettings : undefined}
        role={canManageInterest ? 'button' : undefined}
        tabIndex={canManageInterest ? 0 : undefined}
        aria-label={canManageInterest ? 'Configure interest rate' : undefined}
        onKeyDown={canManageInterest ? onActivateKey(onOpenInterestSettings) : undefined}
        title={canManageInterest ? 'Click to configure interest rate' : undefined}
      >
        <p className="text-xs text-ink-3 uppercase tracking-wide mb-1">Interest Rate</p>
        {client.parentClientId != null ? (
          <p className="text-sm font-medium text-ink-3">Set on parent account</p>
        ) : (
          <p className={`text-lg font-bold ${client.monthlyInterestRate > 0 ? 'text-info' : 'text-ink-3'}`}>
            {client.monthlyInterestRate > 0 ? `${client.monthlyInterestRate}%/mo` : '—'}
          </p>
        )}
        {canManageInterest && (
          <p className="text-xs text-info mt-0.5">{client.monthlyInterestRate > 0 ? `${client.interestGraceDays}d grace` : 'Click to set'}</p>
        )}
      </div>
      <div className="ui-card">
        <p className="text-xs text-ink-3 uppercase tracking-wide mb-1">Transactions</p>
        <p className="text-lg font-bold text-ink">
          {transactionCount}
        </p>
      </div>
    </div>
  )
}
