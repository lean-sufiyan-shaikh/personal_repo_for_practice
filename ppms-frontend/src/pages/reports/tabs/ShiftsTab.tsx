import { useState } from 'react'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { reportApi } from '../../../api/reportApi'
import type { ShiftReportLine } from '../../../api/reportApi'
import { Pagination } from '../../../components/Pagination'
import { pageSlice } from '../../../utils/pageSlice'
import { Link } from 'react-router-dom'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import { presetRange } from '../../../utils/datePresets'
import type { DateRange } from '../../../utils/datePresets'
import { shiftUrl } from '../../../utils/drillDown'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DateRangeInputs, EmptyRangeSuggestion, ErrorBox, PrintHeader } from '../shared'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { shiftStatusBadge } from '../../../components/statusBadges'
import { MoneyText } from '../../../components/AmountText'
import type { ShiftStatus } from '../../../types/shift'
import { codeLabel } from '../../../constants/labels'

const CSV_COLUMNS: CsvColumn<ShiftReportLine>[] = [
  { header: 'Shift', value: (row) => row.id, kind: 'number' },
  { header: 'Date', value: (row) => row.shiftDate, kind: 'date' },
  { header: 'Window', value: (row) => row.shiftName },
  { header: 'Status', value: (row) => codeLabel(row.status) },
  { header: 'Amount due', value: (row) => row.totalAmountDue, kind: 'money' },
  { header: 'Cash', value: (row) => row.cashCollected, kind: 'money' },
  { header: 'UPI', value: (row) => row.upiCollected, kind: 'money' },
  { header: 'Card', value: (row) => row.cardCollected, kind: 'money' },
  { header: 'Credit', value: (row) => row.creditTotal, kind: 'money' },
  { header: 'Discrepancy type', value: (row) => row.discrepancyType },
  { header: 'Discrepancy amount', value: (row) => row.discrepancyAmount, kind: 'money' },
]

function discrepancyCell(s: ShiftReportLine) {
  if (!s.discrepancyAmount || s.discrepancyAmount <= 0) return <span className="text-ink-3">—</span>
  return (
    <span className={`text-xs font-semibold ${s.discrepancyType === 'SHORT' ? 'text-bad' : 'text-warn'}`}>
      {s.discrepancyType} <MoneyText value={s.discrepancyAmount} />
    </span>
  )
}

const PRINT_COLUMNS: DataTableColumn<ShiftReportLine>[] = [
  { key: 'date', header: 'Date', cell: (s) => <span className="text-ink-2">{s.shiftDate}</span> },
  { key: 'window', header: 'Window', cell: (s) => <span className="text-ink-3">{s.shiftName ?? '—'}</span> },
  { key: 'status', header: 'Status', cell: (s) => s.status ? <StatusBadge {...shiftStatusBadge(s.status as ShiftStatus)} /> : '—' },
  { key: 'due', header: 'Amount Due', numeric: true, cell: (s) => <MoneyText value={s.totalAmountDue} className="font-medium text-ink" /> },
  { key: 'cash', header: 'Cash', numeric: true, cell: (s) => <MoneyText value={s.cashCollected} className="text-ink-2" /> },
  { key: 'upi', header: 'UPI', numeric: true, cell: (s) => <MoneyText value={s.upiCollected} className="text-ink-2" /> },
  { key: 'card', header: 'Card', numeric: true, cell: (s) => <MoneyText value={s.cardCollected} className="text-ink-2" /> },
  { key: 'credit', header: 'Credit', numeric: true, cell: (s) => <MoneyText value={s.creditTotal} tone="warn" /> },
  { key: 'discrepancy', header: 'Discrepancy', numeric: true, cell: discrepancyCell },
]

const COLUMNS: DataTableColumn<ShiftReportLine>[] = [
  { key: 'shift', header: 'Shift', cell: (s) => <Link to={shiftUrl(s.id)} className="ui-link">Shift #{s.id}</Link> },
  ...PRINT_COLUMNS,
]

// ── Shifts Tab ────────────────────────────────────────────────────────────────

export function ShiftsTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery, isDefaultRange] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)
  const [page, setPage]   = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data, isFetching, error } = useQuery<ShiftReportLine[]>({
    queryKey: ['report-shifts', pumpId, query.from, query.to],
    queryFn:  () => reportApi.getShiftsReport(pumpId, query.from, query.to),
  })

  const rows  = data ?? []
  const paged = data ? pageSlice(rows, page, pageSize) : null

  const applyRange = (range: DateRange) => {
    setFrom(range.from)
    setTo(range.to)
    setQuery(range)
    setPage(0)
  }

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
        <CsvExportButton filename={csvFilename('shifts', query)} columns={CSV_COLUMNS} rows={rows} />
        </div>
      </div>

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {/* Screen: paginated data */}
      {data && paged && (
        rows.length === 0 ? (
          isDefaultRange
            ? <div className="print:hidden"><EmptyRangeSuggestion what="closed shifts" onShowLastMonth={() => applyRange(presetRange('lastMonth'))} /></div>
            : <p className="ui-empty py-4 print:hidden">No closed shifts in this date range.</p>
        ) : (
          <div className="space-y-2 print:hidden">
            <DataTable
              caption="Shifts"
              captionHidden
              columns={COLUMNS}
              rows={paged.content}
              rowKey={(s) => s.id}
              scroll
            />
            <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
            <p className="text-xs text-ink-3">{rows.length} shift{rows.length !== 1 ? 's' : ''} in range.</p>
          </div>
        )
      )}

      {/* Print: full dataset */}
      {data && rows.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Shifts Report" from={query.from} to={query.to} />
          <DataTable
            caption="Shifts report"
            captionHidden
            columns={PRINT_COLUMNS}
            rows={rows}
            rowKey={(s) => s.id}
          />
          <p className="text-xs text-ink-3">{rows.length} shift{rows.length !== 1 ? 's' : ''} in range.</p>
        </div>
      )}
    </div>
  )
}
