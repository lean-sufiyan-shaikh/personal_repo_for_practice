import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import { useAuthStore } from '../../../store/authStore'
import type { Shift } from '../../../types/shift'
import { ResolveDiscrepancyModal } from '../modals/ResolveDiscrepancyModal'
import { SkeletonRows } from '../../../components/Skeleton'
import { Pagination } from '../../../components/Pagination'
import { FilterBar } from '../../../components/layout/FilterBar'
import { pageSlice } from '../../../utils/pageSlice'
import { parseApiError } from '../../../utils/apiError'
import { PAGE_SIZE, awaitingOwnerApprovalLabel, discrepancyAction, investigationLabel, resolutionLabel } from '../shiftLabels'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { discrepancyBadge, shiftStatusBadge } from '../../../components/statusBadges'
import { MoneyText } from '../../../components/AmountText'
import { FULL_HISTORY_QUERY_OPTIONS } from '../../../queryClient'
import type { UserRole } from '../../../types/auth'
import { DiscrepancyBadge } from '../components/DiscrepancyBadge'

const RESOLUTION_TONE: Record<string, StatusTone> = {
  CASH_RECOVERY:    'ok',
  SALARY_DEDUCTION: 'info',
  WAIVED:           'neutral',
}

function DiscrepancyStatus({ shift }: { shift: Shift }) {
  const investigation = investigationLabel(shift)
  return (
    <>
      {shift.discrepancyResolution ? (
        <StatusBadge
          tone={RESOLUTION_TONE[shift.discrepancyResolution] ?? 'warn'}
          label={resolutionLabel(shift.discrepancyResolution)}
        />
      ) : (
        <StatusBadge {...shiftStatusBadge(shift.status)} />
      )}
      {investigation && (
        <span className="block text-xs text-warn font-medium mt-1">{investigation}</span>
      )}
    </>
  )
}

function DiscrepancyAction({ shift, role, onOpen, className = '' }: {
  shift: Shift; role: UserRole | undefined; onOpen: (shift: Shift) => void; className?: string
}) {
  const { t } = useTranslation(['shifts', 'common'])
  const action = discrepancyAction(shift, role)
  if (action === 'resolve') {
    return (
      <button type="button" onClick={() => onOpen(shift)}
        aria-label={t('history.discrepancies.resolveLabel', { id: shift.id })}
        className={`ui-btn ui-btn-primary ${className}`}>
        {t('history.resolve')}
      </button>
    )
  }
  if (action === 'approve') {
    return (
      <button type="button" onClick={() => onOpen(shift)}
        aria-label={t('history.discrepancies.approveLabel', { id: shift.id })}
        className={`ui-btn ui-btn-primary ${className}`}>
        {t('common:actions.approve')}
      </button>
    )
  }
  if (action === 'awaiting-owner') {
    return <span className="text-xs text-warn font-medium">{awaitingOwnerApprovalLabel()}</span>
  }
  return null
}

/** The initial filters come from the address, e.g. a "View short shifts" link on the Short / Over report. */
export function DiscrepanciesHistory({ pumpId, initialFrom = '', initialTo = '', initialType = 'ALL' }: {
  pumpId: number
  initialFrom?: string
  initialTo?: string
  initialType?: 'ALL' | 'SHORT' | 'OVER'
}) {
  const { t } = useTranslation(['shifts', 'common'])
  const [page,     setPage]     = useState(0)
  const [pageSize, setPageSize] = useState(PAGE_SIZE)
  const [fromDate, setFromDate] = useState(initialFrom)
  const [toDate,   setToDate]   = useState(initialTo)
  const [typeFilter, setTypeFilter] = useState<'ALL' | 'SHORT' | 'OVER'>(initialType)
  const [resolvingShift, setResolvingShift] = useState<Shift | null>(null)
  const role = useAuthStore((state) => state.user?.role)

  const { data: filtered = [], isLoading, error } = useQuery({
    queryKey: ['shiftHistory', pumpId, 'discrepancies', fromDate, toDate, typeFilter],
    queryFn:  () => shiftApi.getShiftHistory(pumpId, {
      from: fromDate || undefined,
      to: toDate || undefined,
      discrepanciesOnly: true,
      discrepancyType: typeFilter === 'ALL' ? undefined : typeFilter,
    }),
    ...FULL_HISTORY_QUERY_OPTIONS,
  })
  const hasFilter = !!fromDate || !!toDate || typeFilter !== 'ALL'

  const pageData = pageSlice(filtered, page, pageSize)
  const paged    = pageData.content

  const clearFilter = () => { setFromDate(''); setToDate(''); setTypeFilter('ALL'); setPage(0) }

  const totalShort = filtered.filter(s => s.discrepancyType === 'SHORT').reduce((sum, s) => sum + (s.discrepancyAmount ?? 0), 0)
  const totalOver  = filtered.filter(s => s.discrepancyType === 'OVER').reduce((sum, s) => sum + (s.discrepancyAmount ?? 0), 0)
  const resolved   = filtered.filter(s => s.discrepancyResolution).length
  const cashRecovered = filtered.filter(s => s.discrepancyResolution === 'CASH_RECOVERY').reduce((sum, s) => sum + (s.cashRecoveryAmount ?? 0), 0)

  const columns: DataTableColumn<Shift>[] = [
    { key: 'id', header: t('history.columns.id'), className: 'px-5 hidden md:table-cell', cell: (shift) => <span className="text-ink-3 text-xs font-mono">{shift.id}</span> },
    { key: 'date', header: t('history.columns.date'), className: 'px-5', cell: (shift) => <span className="text-xs text-ink-2">{shift.shiftDate}</span> },
    { key: 'operator', header: t('history.columns.operator'), className: 'px-5', cell: (shift) => <span className="text-sm text-ink font-medium">{shift.operatorName}</span> },
    { key: 'du', header: t('history.discrepancies.columns.du'), className: 'px-5 hidden md:table-cell', cell: (shift) => <span className="text-xs text-ink-3">{shift.duName ?? t('common:duNumber', { number: shift.duNumber })}</span> },
    { key: 'type', header: t('history.discrepancies.columns.type'), className: 'px-5', cell: (shift) => <StatusBadge {...discrepancyBadge(shift.discrepancyType)} /> },
    {
      key: 'amount', header: t('history.discrepancies.columns.amount'), numeric: true, className: 'px-5', cell: (shift) => (
        <>
          <MoneyText value={shift.discrepancyAmount} tone={shift.discrepancyType === 'SHORT' ? 'bad' : 'warn'} className="text-sm font-semibold" />
          {shift.cashRecoveryAmount != null && shift.cashRecoveryAmount > 0 && (
            <span className="block text-xs text-ok font-normal mt-0.5">
              {t('history.discrepancies.recovered')} <MoneyText value={shift.cashRecoveryAmount} />
            </span>
          )}
        </>
      ),
    },
    { key: 'status', header: t('history.columns.status'), className: 'px-5', cell: (shift) => <DiscrepancyStatus shift={shift} /> },
    {
      key: 'note', header: t('history.discrepancies.columns.note'), className: 'px-5 hidden md:table-cell max-w-xs truncate text-xs text-ink-3',
      cell: (shift) => shift.discrepancyResolutionNote ?? shift.investigationNote ?? shift.discrepancyReason ?? '—',
    },
    {
      key: 'action', header: <span className="sr-only">{t('history.discrepancies.columns.action')}</span>, align: 'right', className: 'px-5',
      cell: (shift) => <DiscrepancyAction shift={shift} role={role} onOpen={setResolvingShift} className="ui-btn-compact" />,
    },
  ]

  if (isLoading) return <div className="px-5 py-4"><SkeletonRows count={4} /></div>
  if (error) return <p role="alert" className="ui-alert ui-alert-danger m-4 text-sm">{parseApiError(error, t('history.discrepancies.loadFailed'))}</p>
  if (filtered.length === 0 && !hasFilter) {
    return <div className="ui-empty p-6">{t('history.discrepancies.empty')}</div>
  }

  return (
    <div>
      {/* Summary bar */}
      <div className="flex flex-wrap gap-4 px-6 py-3 border-t border-border bg-bad-bg">
        <div className="text-xs">
          <span className="text-ink-3">{t('history.discrepancies.shortTotal')}</span>
          <MoneyText value={totalShort} tone="bad" className="font-semibold" />
        </div>
        <div className="text-xs">
          <span className="text-ink-3">{t('history.discrepancies.overTotal')}</span>
          <MoneyText value={totalOver} tone="warn" className="font-semibold" />
        </div>
        <div className="text-xs">
          <span className="text-ink-3">{t('history.discrepancies.resolved')}</span>
          <span className="font-semibold text-ink tabular-nums">{resolved} / {filtered.length}</span>
        </div>
        {cashRecovered > 0 && (
          <div className="text-xs">
            <span className="text-ink-3">{t('history.discrepancies.cashRecovered')}</span>
            <MoneyText value={cashRecovered} tone="ok" className="font-semibold" />
          </div>
        )}
      </div>

      <FilterBar
        label={t('history.discrepancies.filtersLabel')}
        onClearFilters={clearFilter}
        hasActiveFilters={hasFilter}
        summary={t('history.shiftCount', { count: filtered.length })}
      >
        <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
          <input aria-label={t('common:fields.fromDate')} type="date" value={fromDate}
            onChange={(e) => { setFromDate(e.target.value); setPage(0) }} className="min-w-[8.5rem] flex-1 text-xs" />
          <span className="text-xs text-ink-3">{t('history.to')}</span>
          <input aria-label={t('common:fields.toDate')} type="date" value={toDate}
            onChange={(e) => { setToDate(e.target.value); setPage(0) }} className="min-w-[8.5rem] flex-1 text-xs" />
        </div>
        <div className="ui-chip-row" role="group" aria-label={t('history.discrepancies.columns.type')}>
          {(['ALL', 'SHORT', 'OVER'] as const).map(type => (
            <button key={type} type="button" className="ui-chip" aria-pressed={typeFilter === type}
              onClick={() => { setTypeFilter(type); setPage(0) }}>
              {type === 'SHORT' ? t('common:badges.short') : type === 'OVER' ? t('common:badges.over') : t('history.discrepancies.all')}
            </button>
          ))}
        </div>
      </FilterBar>

      {/* Cards below md */}
      <ul className="md:hidden divide-y divide-border border-t border-border" aria-label={t('history.discrepancies.caption')}>
        {paged.length === 0 ? (
          <li className="ui-empty px-4 py-6">{t('history.discrepancies.noMatch')}</li>
        ) : (
          paged.map((shift) => {
            const note = shift.discrepancyResolutionNote ?? shift.investigationNote ?? shift.discrepancyReason
            return (
              <li key={shift.id} data-testid="discrepancy-card" className="space-y-2 bg-surface px-4 py-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-ink">{shift.operatorName}</p>
                    <p className="text-xs text-ink-3">{shift.shiftDate} · {shift.shiftWindow}</p>
                  </div>
                  <DiscrepancyBadge type={shift.discrepancyType} amount={shift.discrepancyAmount ?? 0} />
                </div>
                <div><DiscrepancyStatus shift={shift} /></div>
                {shift.cashRecoveryAmount != null && shift.cashRecoveryAmount > 0 && (
                  <p className="text-xs text-ok">{t('history.discrepancies.recovered')} <MoneyText value={shift.cashRecoveryAmount} /></p>
                )}
                {note && <p className="text-xs text-ink-2">{note}</p>}
                <DiscrepancyAction shift={shift} role={role} onOpen={setResolvingShift} className="w-full" />
              </li>
            )
          })
        )}
      </ul>

      <DataTable
        caption={t('history.discrepancies.caption')}
        captionHidden
        columns={columns}
        rows={paged}
        rowKey={(shift) => shift.id}
        empty={<p className="ui-empty px-6 py-6">{t('history.discrepancies.noMatch')}</p>}
        className="hidden md:block rounded-none border-x-0 border-b-0"
      />

      {resolvingShift && (
        <ResolveDiscrepancyModal shift={resolvingShift} onClose={() => setResolvingShift(null)} />
      )}

      <Pagination
        data={pageData}
        onPageChange={setPage}
        onPageSizeChange={(size) => { setPageSize(size); setPage(0) }}
      />
    </div>
  )
}
