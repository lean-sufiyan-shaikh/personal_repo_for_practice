import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { approvalApi } from '../../api/approvalApi'
import type { ApprovalDecisionResult, ApprovalItem } from '../../api/approvalApi'
import { usePumpStore } from '../../store/usePumpStore'
import { useNow } from '../../hooks/useNow'
import { SkeletonTable } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { Spinner } from '../../components/Spinner'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { parseApiError } from '../../utils/apiError'
import { formatCurrency } from '../../utils/format'
import { withToggled } from '../../utils/setToggle'
import { MoneyText } from '../../components/AmountText'
import { ApprovalsTable } from './ApprovalsTable'
import { approvalKey } from './approvalFormat'

type KindFilter = 'all' | 'expenses' | 'payments'

const KIND_FILTERS: { value: KindFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'expenses', label: 'Expenses' },
  { value: 'payments', label: 'Credit payments' },
]

function kindFilterOf(value: string | null): KindFilter {
  return value === 'expenses' || value === 'payments' ? value : 'all'
}

function matchesKind(item: ApprovalItem, filter: KindFilter) {
  if (filter === 'expenses') return item.kind === 'EXPENSE'
  if (filter === 'payments') return item.kind === 'CREDIT_PAYMENT'
  return true
}

function outcomeText(action: 'APPROVED' | 'REJECTED', result: ApprovalDecisionResult) {
  const done = `${result.succeeded} item${result.succeeded !== 1 ? 's' : ''} ${action === 'APPROVED' ? 'approved' : 'rejected'}.`
  return result.failed > 0 ? `${done} ${result.failed} could not be ${action === 'APPROVED' ? 'approved' : 'rejected'}:` : done
}

/** Expenses and credit payments waiting for an Owner or Admin, decided one by one or many at once. */
export default function ApprovalsPage() {
  const pumpId = usePumpStore(state => state.selectedPumpId)
  const qc = useQueryClient()
  const now = useNow(60_000)
  const [searchParams, setSearchParams] = useSearchParams()
  const kindFilter = kindFilterOf(searchParams.get('kind'))
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [rejecting, setRejecting] = useState(false)
  const [rejectError, setRejectError] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<{ text: string; failures: string[] } | null>(null)

  const { data: items = [], isLoading, isError } = useQuery({
    queryKey: ['approvals', pumpId],
    queryFn: () => approvalApi.getPending(pumpId!),
    enabled: pumpId != null,
  })

  const shown = items.filter(item => matchesKind(item, kindFilter))
  const selected = shown.filter(item => selectedKeys.has(approvalKey(item)) && item.blockedReason === null)
  const shownTotal = shown.reduce((sum, item) => sum + item.amount, 0)
  const selectedTotal = selected.reduce((sum, item) => sum + item.amount, 0)

  const decision = useMutation({
    mutationFn: ({ action, reason }: { action: 'APPROVED' | 'REJECTED'; reason?: string }) =>
      approvalApi.decide(pumpId!, selected.map(({ kind, id }) => ({ kind, id })), action, reason),
    onSuccess: (result, { action }) => {
      const itemByKey = new Map(items.map(item => [approvalKey(item), item]))
      setOutcome({
        text: outcomeText(action, result),
        failures: result.results.filter(item => !item.succeeded).map(item => {
          const source = itemByKey.get(approvalKey(item))
          const name = source ? `${source.kind === 'EXPENSE' ? 'Expense' : 'Payment'} ${formatCurrency(source.amount)}` : `#${item.id}`
          return `${name}: ${item.message ?? 'not saved'}`
        }),
      })
      setSelectedKeys(new Set())
      setRejecting(false)
      for (const key of ['approvals', 'expenses', 'credit-pending-payments', 'credit-ledger', 'attention']) {
        qc.invalidateQueries({ queryKey: [key] })
      }
    },
    onError: (err, { action }) => {
      const message = parseApiError(err, action === 'APPROVED' ? 'Could not approve the selected items' : 'Could not reject the selected items')
      if (action === 'REJECTED') setRejectError(message)
      else setOutcome({ text: message, failures: [] })
    },
  })

  const setKindFilter = (value: KindFilter) => {
    setSelectedKeys(new Set())
    setSearchParams(value === 'all' ? {} : { kind: value }, { replace: true })
  }

  const toggleAll = () => {
    const selectable = shown.filter(item => item.blockedReason === null)
    const everySelected = selectable.every(item => selectedKeys.has(approvalKey(item)))
    setSelectedKeys(everySelected ? new Set() : new Set(selectable.map(approvalKey)))
  }

  return (
    <div className="ui-page space-y-5">
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">Owner and Admin</p>
          <h1 className="ui-title-sm">Approvals</h1>
          <p className="ui-subtitle">Expenses and credit payments that need your decision before they count</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Waiting</span>
            <span className="ui-section-meta-value">{shown.length}</span>
          </div>
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Amount</span>
            <MoneyText value={shownTotal} className="ui-section-meta-value" />
          </div>
        </div>
      </div>

      <div role="group" aria-label="Show" className="ui-tabbar w-fit">
        {KIND_FILTERS.map(filter => (
          <button key={filter.value} type="button" aria-pressed={kindFilter === filter.value}
            onClick={() => setKindFilter(filter.value)}
            className={`ui-tabbar__button ${kindFilter === filter.value ? 'ui-tabbar__button--active' : ''}`}>
            {filter.label}
          </button>
        ))}
      </div>

      <div role="status" aria-live="polite"
        className={!outcome ? 'sr-only' : `ui-alert text-sm ${outcome.failures.length > 0 ? 'ui-alert-warning' : 'ui-alert-success'}`}>
        {outcome && (
          <>
            <p>{outcome.text}</p>
            {outcome.failures.length > 0 && (
              <ul className="mt-1 list-disc pl-5">
                {outcome.failures.map(failure => <li key={failure}>{failure}</li>)}
              </ul>
            )}
          </>
        )}
      </div>

      {pumpId == null ? (
        <div className="ui-card"><EmptyState icon="generic" title="No pump selected" subtitle="Choose a pump in the top bar." /></div>
      ) : isLoading ? (
        <div className="ui-card"><SkeletonTable rows={4} cols={6} /></div>
      ) : isError ? (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">Could not load the approval queue. Refresh to try again.</div>
      ) : shown.length === 0 ? (
        <div className="ui-card">
          <EmptyState icon="generic" title="Nothing is waiting for approval" subtitle="New expenses and payments above your approval limit will appear here." />
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-ink-2">
              {selected.length > 0 ? `${selected.length} selected · ${formatCurrency(selectedTotal)}` : 'Select items to approve or reject them together.'}
            </p>
            <div className="flex gap-2">
              <button type="button" className="ui-btn ui-btn-danger" disabled={selected.length === 0 || decision.isPending}
                onClick={() => { setRejectError(null); setRejecting(true) }}>
                Reject selected
              </button>
              <button type="button" className="ui-btn ui-btn-primary" disabled={selected.length === 0 || decision.isPending}
                onClick={() => decision.mutate({ action: 'APPROVED' })}>
                {decision.isPending && !rejecting ? <span className="flex items-center gap-1.5"><Spinner />Approving…</span> : 'Approve selected'}
              </button>
            </div>
          </div>
          <ApprovalsTable items={shown} selectedKeys={selectedKeys} now={now}
            onToggle={item => setSelectedKeys(previous => withToggled(previous, approvalKey(item)))}
            onToggleAll={toggleAll} />
        </div>
      )}

      {rejecting && (
        <RejectReasonDialog
          title={`Reject ${selected.length} item${selected.length !== 1 ? 's' : ''}`}
          subtitle={`${formatCurrency(selectedTotal)} in total; the reason is saved with each rejection`}
          isPending={decision.isPending}
          error={rejectError}
          onConfirm={reason => decision.mutate({ action: 'REJECTED', reason })}
          onClose={() => setRejecting(false)}
        />
      )}
    </div>
  )
}
