import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { approvalApi } from '../../api/approvalApi'
import type { ApprovalDecisionResult, ApprovalItem } from '../../api/approvalApi'
import { usePumpStore } from '../../store/usePumpStore'
import { useNow } from '../../hooks/useNow'
import { SkeletonTable } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { Spinner } from '../../components/Spinner'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { parseApiError, translatedErrorCode } from '../../utils/apiError'
import { formatCurrency } from '../../utils/format'
import { withToggled } from '../../utils/setToggle'
import { MoneyText } from '../../components/AmountText'
import { ApprovalsTable } from './ApprovalsTable'
import { approvalKey } from './approvalFormat'

type KindFilter = 'all' | 'expenses' | 'payments'

const KIND_FILTERS: KindFilter[] = ['all', 'expenses', 'payments']

function kindFilterOf(value: string | null): KindFilter {
  return value === 'expenses' || value === 'payments' ? value : 'all'
}

function matchesKind(item: ApprovalItem, filter: KindFilter) {
  if (filter === 'expenses') return item.kind === 'EXPENSE'
  if (filter === 'payments') return item.kind === 'CREDIT_PAYMENT'
  return true
}

function outcomeText(t: TFunction<['approvals', 'common']>, action: 'APPROVED' | 'REJECTED', result: ApprovalDecisionResult) {
  const approved = action === 'APPROVED'
  const done = t(approved ? 'outcome.approved' : 'outcome.rejected', { count: result.succeeded })
  if (result.failed === 0) return done
  return `${done} ${t(approved ? 'outcome.notApproved' : 'outcome.notRejected', { count: result.failed })}`
}

/** Expenses and credit payments waiting for an Owner or Admin, decided one by one or many at once. */
export default function ApprovalsPage() {
  const { t } = useTranslation(['approvals', 'common'])
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
        text: outcomeText(t, action, result),
        failures: result.results.filter(item => !item.succeeded).map(item => {
          const source = itemByKey.get(approvalKey(item))
          const name = source
            ? t(source.kind === 'EXPENSE' ? 'outcome.expenseName' : 'outcome.paymentName', { amount: formatCurrency(source.amount) })
            : `#${item.id}`
          return `${name}: ${translatedErrorCode(item.code, item.params) ?? item.message ?? t('outcome.notSaved')}`
        }),
      })
      setSelectedKeys(new Set())
      setRejecting(false)
      for (const key of ['approvals', 'expenses', 'credit-pending-payments', 'credit-ledger', 'attention']) {
        qc.invalidateQueries({ queryKey: [key] })
      }
    },
    onError: (err, { action }) => {
      const message = parseApiError(err, action === 'APPROVED' ? t('errors.approveFailed') : t('errors.rejectFailed'))
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
          <p className="ui-section-kicker">{t('page.kicker')}</p>
          <h1 className="ui-title-sm">{t('page.title')}</h1>
          <p className="ui-subtitle">{t('page.subtitle')}</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.waiting')}</span>
            <span className="ui-section-meta-value">{shown.length}</span>
          </div>
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.amount')}</span>
            <MoneyText value={shownTotal} className="ui-section-meta-value" />
          </div>
        </div>
      </div>

      <div role="group" aria-label={t('filters.label')} className="ui-tabbar w-fit">
        {KIND_FILTERS.map(filter => (
          <button key={filter} type="button" aria-pressed={kindFilter === filter}
            onClick={() => setKindFilter(filter)}
            className={`ui-tabbar__button ${kindFilter === filter ? 'ui-tabbar__button--active' : ''}`}>
            {t(`filters.${filter}`)}
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
        <div className="ui-card"><EmptyState icon="generic" title={t('common:notices.noPumpTitle')} subtitle={t('page.noPumpSubtitle')} /></div>
      ) : isLoading ? (
        <div className="ui-card"><SkeletonTable rows={4} cols={6} /></div>
      ) : isError ? (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('errors.loadFailed')}</div>
      ) : shown.length === 0 ? (
        <div className="ui-card">
          <EmptyState icon="generic" title={t('page.emptyTitle')} subtitle={t('page.emptySubtitle')} />
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-ink-2">
              {selected.length > 0 ? t('selection.summary', { count: selected.length, amount: formatCurrency(selectedTotal) }) : t('selection.hint')}
            </p>
            <div className="flex gap-2">
              <button type="button" className="ui-btn ui-btn-danger" disabled={selected.length === 0 || decision.isPending}
                onClick={() => { setRejectError(null); setRejecting(true) }}>
                {t('selection.reject')}
              </button>
              <button type="button" className="ui-btn ui-btn-primary" disabled={selected.length === 0 || decision.isPending}
                onClick={() => decision.mutate({ action: 'APPROVED' })}>
                {decision.isPending && !rejecting ? <span className="flex items-center gap-1.5"><Spinner />{t('selection.approving')}</span> : t('selection.approve')}
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
          title={t('rejectDialog.title', { count: selected.length })}
          subtitle={t('rejectDialog.subtitle', { amount: formatCurrency(selectedTotal) })}
          isPending={decision.isPending}
          error={rejectError}
          onConfirm={reason => decision.mutate({ action: 'REJECTED', reason })}
          onClose={() => setRejecting(false)}
        />
      )}
    </div>
  )
}
