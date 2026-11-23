import { useState } from 'react'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { reportApi } from '../../../api/reportApi'
import type { ExpenseReportLine } from '../../../api/reportApi'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { Pagination } from '../../../components/Pagination'
import { PeriodChangeNote } from '../../../components/PeriodChangeNote'
import { pageSlice } from '../../../utils/pageSlice'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import { presetRange, previousPeriod } from '../../../utils/datePresets'
import type { DateRange } from '../../../utils/datePresets'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DateRangeInputs, EmptyRangeSuggestion, ErrorBox, PrintHeader, SummaryCard } from '../shared'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { approvalStatusBadge } from '../../../components/statusBadges'
import { MoneyText } from '../../../components/AmountText'
import type { ExpenseApprovalStatus } from '../../../api/expenseApi'
import { codeLabel } from '../../../constants/labels'

const CSV_COLUMNS: CsvColumn<ExpenseReportLine>[] = [
  { header: 'Date', value: (row) => row.expenseDate, kind: 'date' },
  { header: 'Category', value: (row) => codeLabel(row.category) },
  { header: 'Description', value: (row) => row.description },
  { header: 'Amount', value: (row) => row.amount, kind: 'money' },
  { header: 'Status', value: (row) => codeLabel(row.approvalStatus) },
]

function approvedTotal(rows: ExpenseReportLine[]): number {
  return rows.filter(e => e.approvalStatus === 'APPROVED').reduce((sum, e) => sum + e.amount, 0)
}

// ── Expenses Tab ──────────────────────────────────────────────────────────────

const COLUMNS: DataTableColumn<ExpenseReportLine>[] = [
  { key: 'date', header: 'Date', cell: (e) => <span className="text-ink-2">{e.expenseDate}</span> },
  { key: 'category', header: 'Category', cell: (e) => <span className="text-ink-3 text-xs">{codeLabel(e.category)}</span> },
  { key: 'description', header: 'Description', cell: (e) => <span className="text-ink">{e.description}</span> },
  { key: 'amount', header: 'Amount', numeric: true, cell: (e) => <MoneyText value={e.amount} tone="bad" className="font-medium" /> },
  {
    key: 'status', header: 'Status',
    cell: (e) => e.approvalStatus ? <StatusBadge {...approvalStatusBadge(e.approvalStatus as ExpenseApprovalStatus)} /> : '—',
  },
]

export function ExpensesTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery, isDefaultRange] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)
  const [page, setPage]   = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data, isFetching, error } = useQuery<ExpenseReportLine[]>({
    queryKey: ['report-expenses', pumpId, query.from, query.to],
    queryFn:  () => reportApi.getExpensesReport(pumpId, query.from, query.to),
  })

  const rows  = data ?? []
  const paged = data ? pageSlice(rows, page, pageSize) : null
  const total = approvedTotal(rows)

  const previousRange = previousPeriod(query)
  const { data: previous } = useQuery<ExpenseReportLine[]>({
    queryKey: ['report-expenses', pumpId, previousRange.from, previousRange.to],
    queryFn:  () => reportApi.getExpensesReport(pumpId, previousRange.from, previousRange.to),
    enabled: !!data,
  })

  const applyRange = (range: DateRange) => {
    setFrom(range.from)
    setTo(range.to)
    setQuery(range)
    setPage(0)
  }
  const pendingTotal = rows
    .filter(e => e.approvalStatus === 'PENDING_APPROVAL')
    .reduce((sum, e) => sum + e.amount, 0)

  return (
    <div className="space-y-4">
      <div className="ui-card ui-card-muted p-4 print:hidden">
        <div className="ui-filter-group">
        <DateRangeInputs from={from} to={to} onFromChange={setFrom} onToChange={setTo} onPreset={applyRange} />
        <button
          onClick={() => { setQuery({ from, to }); setPage(0) }}
          disabled={isFetching}
          className="ui-btn ui-btn-primary"
        >
          {isFetching ? 'Loading…' : 'Generate'}
        </button>
        <button
          onClick={() => window.print()}
          disabled={rows.length === 0}
          className="ui-btn ui-btn-secondary"
        >
          Print PDF
        </button>
        <CsvExportButton filename={csvFilename('expenses', query)} columns={CSV_COLUMNS} rows={rows} />
        </div>
      </div>

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {/* Screen: paginated data */}
      {data && paged && (
        rows.length === 0 ? (
          isDefaultRange
            ? <div className="print:hidden"><EmptyRangeSuggestion what="expenses" onShowLastMonth={() => applyRange(presetRange('lastMonth'))} /></div>
            : <p className="ui-empty py-4 print:hidden">No expenses in this date range.</p>
        ) : (
          <div className="space-y-3 print:hidden">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <SummaryCard label="Approved Expenses" value={<MoneyText value={total} />} color="text-bad"
                note={previous && <PeriodChangeNote current={total} previous={approvedTotal(previous)} higherIsBetter={false} />} />
              <SummaryCard label="Pending Approval" value={<MoneyText value={pendingTotal} />} color="text-warn" />
            </div>
            <DataTable caption="Expenses" captionHidden columns={COLUMNS} rows={paged.content} rowKey={(e) => e.id} scroll />
            <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
            <p className="text-xs text-ink-3">{rows.length} expense{rows.length !== 1 ? 's' : ''} in range.</p>
          </div>
        )
      )}

      {/* Print: full dataset */}
      {data && rows.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Expenses Report" from={query.from} to={query.to} />
          <SummaryCard label="Approved Expenses" value={<MoneyText value={total} />} color="text-bad" />
          <SummaryCard label="Pending Approval" value={<MoneyText value={pendingTotal} />} color="text-warn" />
          <DataTable caption="Expenses report" captionHidden columns={COLUMNS} rows={rows} rowKey={(e) => e.id} />
          <p className="text-xs text-ink-3">{rows.length} expense{rows.length !== 1 ? 's' : ''} in range.</p>
        </div>
      )}
    </div>
  )
}
