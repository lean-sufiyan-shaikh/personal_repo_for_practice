import { useState } from 'react'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { userApi } from '../../../api/userApi'
import { reportApi } from '../../../api/reportApi'
import type { OperatorDutyReport, OperatorDutyShiftLine } from '../../../api/reportApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { Pagination } from '../../../components/Pagination'
import { pageSlice } from '../../../utils/pageSlice'
import { DateRangeInputs, ErrorBox, PrintHeader, SummaryCard } from '../shared'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { shiftStatusBadge } from '../../../components/statusBadges'
import { MoneyText } from '../../../components/AmountText'
import type { ShiftStatus } from '../../../types/shift'
import { Link } from 'react-router-dom'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import type { DateRange } from '../../../utils/datePresets'
import { shiftUrl } from '../../../utils/drillDown'
import { codeLabel } from '../../../constants/labels'

const CSV_COLUMNS: CsvColumn<OperatorDutyShiftLine>[] = [
  { header: 'Shift', value: (row) => row.shiftId, kind: 'number' },
  { header: 'Date', value: (row) => row.shiftDate, kind: 'date' },
  { header: 'Window', value: (row) => row.shiftWindow },
  { header: 'Amount due', value: (row) => row.totalAmountDue, kind: 'money' },
  { header: 'Cash', value: (row) => row.cashCollected, kind: 'money' },
  { header: 'UPI', value: (row) => row.upiCollected, kind: 'money' },
  { header: 'Card', value: (row) => row.cardCollected, kind: 'money' },
  { header: 'Credit', value: (row) => row.creditTotal, kind: 'money' },
  { header: 'Discrepancy type', value: (row) => row.discrepancyType },
  { header: 'Discrepancy amount', value: (row) => row.discrepancyAmount, kind: 'money' },
  { header: 'Status', value: (row) => codeLabel(row.status) },
]

const DUTY_COLUMNS: DataTableColumn<OperatorDutyShiftLine>[] = [
  { key: 'shift', header: 'Shift', cell: (s) => <Link to={shiftUrl(s.shiftId)} className="ui-link">Shift #{s.shiftId}</Link> },
  { key: 'date', header: 'Date', cell: (s) => <span className="text-ink-2">{s.shiftDate}</span> },
  { key: 'window', header: 'Window', cell: (s) => <span className="text-ink-3">{s.shiftWindow}</span> },
  { key: 'due', header: 'Amount Due', numeric: true, cell: (s) => <MoneyText value={s.totalAmountDue} className="font-medium text-ink" /> },
  { key: 'cash', header: 'Cash', numeric: true, cell: (s) => <MoneyText value={s.cashCollected} className="text-ink-2" /> },
  { key: 'credit', header: 'Credit', numeric: true, cell: (s) => <MoneyText value={s.creditTotal} tone="warn" /> },
  {
    key: 'discrepancy', header: 'Discrepancy', numeric: true,
    cell: (s) => s.discrepancyAmount > 0 ? (
      <span className={`text-xs font-semibold ${s.discrepancyType === 'SHORT' ? 'text-bad' : 'text-warn'}`}>
        {s.discrepancyType} <MoneyText value={s.discrepancyAmount} />
      </span>
    ) : <span className="text-ink-3">—</span>,
  },
  { key: 'status', header: 'Status', cell: (s) => <StatusBadge {...shiftStatusBadge(s.status as ShiftStatus)} /> },
]

// ── Operator Duty Tab ─────────────────────────────────────────────────────────

export function OperatorDutyTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)
  const [operatorId, setOperatorId] = useState<number | null>(null)
  const [reportOperatorId, setReportOperatorId] = useState<number | null>(null)
  const [page, setPage]   = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data: staffList = [] } = useQuery({
    queryKey: ['staff', pumpId],
    queryFn: () => userApi.getStaff(pumpId),
  })

  const { data, isFetching, error } = useQuery<OperatorDutyReport>({
    queryKey: ['report-duty', pumpId, reportOperatorId, query.from, query.to],
    queryFn: () => reportApi.getOperatorDuty(pumpId, reportOperatorId!, query.from, query.to),
    enabled: reportOperatorId !== null,
  })

  const paged = data ? pageSlice(data.shifts, page, pageSize) : null

  const applyRange = (range: DateRange) => {
    setFrom(range.from)
    setTo(range.to)
    setQuery(range)
    if (operatorId) setReportOperatorId(operatorId)
    setPage(0)
  }

  return (
    <div className="space-y-4">
      {/* Screen: controls */}
      <div className="ui-card ui-card-muted p-4 print:hidden">
        <div className="ui-filter-group">
        <div className="w-56">
          <span className="ui-label">Operator</span>
          <SearchableSelect label="Operator"
            value={operatorId ? String(operatorId) : ''}
            onChange={(v) => setOperatorId(v ? Number(v) : null)}
            options={staffList.filter(s => s.role === 'OPERATOR').map(s => ({ value: String(s.id), label: s.fullName }))}
            placeholder="Select operator…"
          />
        </div>
        <DateRangeInputs from={from} to={to} onFromChange={setFrom} onToChange={setTo} onPreset={applyRange} />
        <button
          onClick={() => { if (operatorId) { setReportOperatorId(operatorId); setQuery({ from, to }) } setPage(0) }}
          disabled={!operatorId || isFetching}
          className="ui-btn ui-btn-primary"
        >
          {isFetching ? 'Loading…' : 'Generate'}
        </button>
        <button
          onClick={() => window.print()}
          disabled={!data || data.shifts.length === 0}
          className="ui-btn ui-btn-secondary"
        >
          Print PDF
        </button>
        <CsvExportButton
          filename={csvFilename(`operator-duty-${data?.operatorName.toLowerCase().replace(/[^a-z0-9]+/g, '-') ?? 'operator'}`, query)}
          columns={CSV_COLUMNS}
          rows={data?.shifts ?? []}
        />
        </div>
      </div>

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {/* Screen: paginated data */}
      {data && paged && (
        <div className="space-y-4 print:hidden">
          <div className="grid grid-cols-3 gap-4">
            <SummaryCard label="Total Shifts"      value={String(data.totalShifts)}      color="text-ink" />
            <SummaryCard label="Total Amount Due"  value={<MoneyText value={data.totalAmountDue} />} color="text-ink" />
            <SummaryCard label="Total Discrepancy" value={<MoneyText value={data.totalDiscrepancy} />} color={data.totalDiscrepancy > 0 ? 'text-bad' : 'text-ok'} />
          </div>
          <DataTable caption="Operator duty" captionHidden columns={DUTY_COLUMNS} rows={paged.content} rowKey={(s) => s.shiftId} scroll />
          <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
        </div>
      )}

      {/* Print: full dataset */}
      {data && data.shifts.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title={`Operator Duty Report — ${data.operatorName}`} from={query.from} to={query.to} />
          <div className="grid grid-cols-3 gap-4">
            <SummaryCard label="Total Shifts"      value={String(data.totalShifts)}      color="text-ink" />
            <SummaryCard label="Total Amount Due"  value={<MoneyText value={data.totalAmountDue} />} color="text-ink" />
            <SummaryCard label="Total Discrepancy" value={<MoneyText value={data.totalDiscrepancy} />} color={data.totalDiscrepancy > 0 ? 'text-bad' : 'text-ok'} />
          </div>
          <DataTable caption="Operator duty" captionHidden columns={DUTY_COLUMNS} rows={data.shifts} rowKey={(s) => s.shiftId} />
        </div>
      )}
    </div>
  )
}
