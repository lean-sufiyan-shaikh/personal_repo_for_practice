import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { auditApi } from '../../api/auditApi'
import type { AuditAction, AuditLog } from '../../api/auditApi'
import { usePumpStore } from '../../store/usePumpStore'
import { Pagination } from '../../components/Pagination'
import { SkeletonRows } from '../../components/Skeleton'
import { Reveal } from '../../components/Reveal'
import { DatePresetChips } from '../../components/DatePresetChips'
import { CsvExportButton } from '../../components/CsvExportButton'
import { useDateRangeParams } from '../../hooks/useDateRangeParams'
import { formatIstDateTime, localDateInputValue } from '../../utils/date'
import { csvFilename } from '../../utils/csv'
import type { CsvColumn } from '../../utils/csv'
import { auditEntityLabel, auditEntityUrl } from '../../utils/drillDown'
import { codeLabel } from '../../constants/labels'
import { AUDIT_ACTION_LABELS, AUDIT_ACTION_STYLES } from './auditActions'

const PAGE_SIZE_OPTIONS = [10, 25, 50]
const EXPORT_PAGE_SIZE = 200
const EXPORT_MAX_PAGES = 25

const ACTION_OPTIONS = (Object.entries(AUDIT_ACTION_LABELS) as [AuditAction, string][])
  .sort(([, a], [, b]) => a.localeCompare(b))

const CSV_COLUMNS: CsvColumn<AuditLog>[] = [
  { header: 'When', value: (log) => formatIstDateTime(log.createdAt, {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }) },
  { header: 'Action', value: (log) => AUDIT_ACTION_LABELS[log.action] ?? codeLabel(log.action) },
  { header: 'By', value: (log) => log.actorName },
  { header: 'Description', value: (log) => log.description },
  { header: 'Entity', value: (log) => log.entityType },
  { header: 'Entity ID', value: (log) => log.entityId },
]

function fmtDateTime(d: string) {
  return formatIstDateTime(d, {
    day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  })
}

export default function AuditLogPage() {
  const [range, setRange] = useDateRangeParams(() => ({ from: localDateInputValue(-1), to: localDateInputValue() }))
  const { from, to } = range
  const [action, setAction]       = useState<AuditAction | ''>('')
  const [actorId, setActorId]     = useState<number | null>(null)
  const [showSignIns, setShowSignIns] = useState(false)
  const [page, setPage]       = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { selectedPumpId: pumpId } = usePumpStore()

  const filters = {
    from: from || undefined,
    to: to || undefined,
    action: action || undefined,
    actorId: actorId ?? undefined,
    hideSignIns: !showSignIns,
  }

  const { data, isLoading } = useQuery({
    queryKey:  ['auditLogs', pumpId, page, pageSize, filters],
    queryFn:   () => auditApi.getAuditLogs({ pumpId: pumpId!, page, size: pageSize, ...filters }),
    enabled: !!pumpId,
  })

  const { data: actors = [] } = useQuery({
    queryKey: ['auditActors', pumpId],
    queryFn: () => auditApi.getActors(pumpId!),
    enabled: !!pumpId,
  })

  const logs         = data?.content            ?? []
  const totalPages   = data?.page?.totalPages   ?? 0
  const totalEvents  = data?.page?.totalElements ?? 0
  const currentPage  = data?.page?.number       ?? page
  const hasFilters   = !!(from || to || action || actorId)

  function applyRange(next: { from: string; to: string }) {
    setRange(next)
    setPage(0)
  }

  function handlePageSizeChange(value: number) {
    setPageSize(value)
    setPage(0)
  }

  function clearFilters() {
    setRange({ from: '', to: '' })
    setAction('')
    setActorId(null)
    setPage(0)
  }

  const loadAllEvents = async (): Promise<AuditLog[]> => {
    const all: AuditLog[] = []
    for (let exportPage = 0; exportPage < EXPORT_MAX_PAGES; exportPage++) {
      const result = await auditApi.getAuditLogs({ pumpId: pumpId!, page: exportPage, size: EXPORT_PAGE_SIZE, ...filters })
      all.push(...result.content)
      if (exportPage + 1 >= result.page.totalPages) break
    }
    return all
  }

  const rangeStart = totalEvents === 0 ? 0 : currentPage * pageSize + 1
  const rangeEnd   = Math.min((currentPage + 1) * pageSize, totalEvents)

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* ── Header ── */}
      <Reveal delay={60}>
      <div className="ui-page-header">
        <div>
        <h1 className="ui-title-sm">Audit Log</h1>
        <p className="ui-subtitle">
          Full history of all sensitive operations — who did what and when
        </p>
        </div>
      </div>
      </Reveal>

      {/* ── Filters ── */}
      <Reveal delay={130}>
      <div className="ui-card ui-form-shell">
        <DatePresetChips range={range} onSelect={applyRange} />
        <div className="grid grid-cols-1 items-end gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label className="block">
            <span className="ui-label mb-0">From</span>
            <input
              type="date"
              value={from}
              onChange={e => applyRange({ from: e.target.value, to })}
              max={to || undefined}
              className="text-sm"
            />
          </label>
          <label className="block">
            <span className="ui-label mb-0">To</span>
            <input
              type="date"
              value={to}
              onChange={e => applyRange({ from, to: e.target.value })}
              min={from || undefined}
              className="text-sm"
            />
          </label>
          <label className="block">
            <span className="ui-label mb-0">Action</span>
            <select
              value={action}
              onChange={e => { setAction(e.target.value as AuditAction | ''); setPage(0) }}
              className="text-sm"
            >
              <option value="">All actions</option>
              {ACTION_OPTIONS.map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="ui-label mb-0">User</span>
            <select
              value={actorId ?? ''}
              onChange={e => { setActorId(e.target.value ? Number(e.target.value) : null); setPage(0) }}
              className="text-sm"
            >
              <option value="">Everyone</option>
              {actors.map(actor => (
                <option key={actor.actorId} value={actor.actorId}>{actor.actorName}</option>
              ))}
            </select>
          </label>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <label className="inline-flex items-center gap-2 text-sm text-ink-2">
            <input
              type="checkbox"
              checked={showSignIns}
              onChange={e => { setShowSignIns(e.target.checked); setPage(0) }}
            />
            Show sign-in events
          </label>
          {hasFilters && (
            <button type="button" onClick={clearFilters} className="ui-btn ui-btn-ghost text-xs">
              Clear filters
            </button>
          )}
          <div className="ml-auto">
            <CsvExportButton
              filename={csvFilename('audit', from && to ? { from, to } : undefined)}
              columns={CSV_COLUMNS}
              rows={loadAllEvents}
              disabled={totalEvents === 0}
            />
          </div>
        </div>
      </div>
      </Reveal>

      {/* ── Log list ── */}
      <Reveal delay={200}>
      <div className="ui-card p-0 overflow-hidden">
        <div className="ui-toolbar">
          <p className="ui-toolbar-title">
            {hasFilters ? 'Filtered Events' : 'All Events'} ({isLoading ? '…' : totalEvents})
          </p>
          {!isLoading && totalPages > 0 && (
            <p className="ui-toolbar-note">
              Page {currentPage + 1} of {totalPages}
            </p>
          )}
        </div>

        {isLoading ? (
          <div className="px-5 py-4"><SkeletonRows count={5} /></div>
        ) : logs.length === 0 ? (
          <p className="ui-empty px-5 py-6">
            No audit events found{hasFilters ? ' for the selected filters' : ''}.
          </p>
        ) : (
          <div className="divide-y divide-slate-100">
            {logs.map(log => {
              const entityUrl = auditEntityUrl(log.entityType, log.entityId)
              return (
                <div key={log.id} className="px-5 py-3">
                  <div className="flex flex-wrap items-center justify-between gap-2 mb-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${AUDIT_ACTION_STYLES[log.action] ?? 'bg-slate-100 text-slate-600'}`}>
                        {AUDIT_ACTION_LABELS[log.action] ?? codeLabel(log.action)}
                      </span>
                      <span className="text-xs text-slate-500">by <span className="font-medium text-slate-700">{log.actorName}</span></span>
                    </div>
                    <p className="text-xs text-slate-500 flex-shrink-0">{fmtDateTime(log.createdAt)}</p>
                  </div>
                  <p className="text-sm text-slate-700">{log.description}</p>
                  {log.entityId && (
                    <p className="text-xs text-slate-500 mt-0.5">
                      {entityUrl
                        ? <Link to={entityUrl} className="ui-link">{auditEntityLabel(log.entityType, log.entityId)}</Link>
                        : auditEntityLabel(log.entityType, log.entityId)}
                    </p>
                  )}
                </div>
              )
            })}
          </div>
        )}

        {/* ── Pagination controls — always shown when there's data ── */}
        {!isLoading && totalEvents > 0 && (
          <div className="px-5 pb-4">
            <Pagination
              data={{
                content: logs,
                page: currentPage,
                pageSize,
                totalElements: totalEvents,
                totalPages,
                hasNext: currentPage < totalPages - 1,
                hasPrevious: currentPage > 0,
              }}
              onPageChange={setPage}
              onPageSizeChange={handlePageSizeChange}
              pageSizeOptions={PAGE_SIZE_OPTIONS}
            />
            <div className="pt-2 text-center">
              <span className="text-xs text-slate-500">
                Showing {rangeStart}–{rangeEnd} of {totalEvents} events
              </span>
            </div>
          </div>
        )}
      </div>
      </Reveal>
    </div>
  )
}
