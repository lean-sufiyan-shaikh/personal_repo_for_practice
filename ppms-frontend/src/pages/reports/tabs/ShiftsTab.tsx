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
import { fmtAmt } from '../reportFormat'
import { ScrollRegion } from '../../../components/ScrollRegion'
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
            <ScrollRegion label="Shifts" className="ui-table-wrap ui-table-wrap--scroll overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-slate-50 text-xs text-slate-500">
                    <th scope="col" className="text-left px-4 py-2.5 font-medium">Shift</th>
                    <th scope="col" className="text-left px-4 py-2.5 font-medium">Date</th>
                    <th scope="col" className="text-left px-4 py-2.5 font-medium">Window</th>
                    <th scope="col" className="text-left px-4 py-2.5 font-medium">Status</th>
                    <th scope="col" className="text-right px-4 py-2.5 font-medium">Amount Due</th>
                    <th scope="col" className="text-right px-4 py-2.5 font-medium">Cash</th>
                    <th scope="col" className="text-right px-4 py-2.5 font-medium">UPI</th>
                    <th scope="col" className="text-right px-4 py-2.5 font-medium">Card</th>
                    <th scope="col" className="text-right px-4 py-2.5 font-medium">Credit</th>
                    <th scope="col" className="text-right px-4 py-2.5 font-medium">Discrepancy</th>
                  </tr>
                </thead>
                <tbody>
                  {paged.content.map(s => (
                    <tr key={s.id} className="border-t border-slate-100 hover:bg-slate-50/50">
                      <td className="px-4 py-2.5"><Link to={shiftUrl(s.id)} className="ui-link">Shift #{s.id}</Link></td>
                      <td className="px-4 py-2.5 text-slate-600">{s.shiftDate}</td>
                      <td className="px-4 py-2.5 text-slate-500">{s.shiftName ?? '—'}</td>
                      <td className="px-4 py-2.5 text-slate-500 text-xs">{codeLabel(s.status)}</td>
                      <td className="px-4 py-2.5 text-right font-medium text-slate-700">{fmtAmt(s.totalAmountDue)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-600">{fmtAmt(s.cashCollected)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-600">{fmtAmt(s.upiCollected)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-600">{fmtAmt(s.cardCollected)}</td>
                      <td className="px-4 py-2.5 text-right text-orange-600">{fmtAmt(s.creditTotal)}</td>
                      <td className={`px-4 py-2.5 text-right text-xs font-semibold ${
                        s.discrepancyAmount && s.discrepancyAmount > 0
                          ? (s.discrepancyType === 'SHORT' ? 'text-red-600' : 'text-amber-600')
                          : 'text-slate-500'
                      }`}>
                        {s.discrepancyAmount && s.discrepancyAmount > 0
                          ? `${s.discrepancyType} ${fmtAmt(s.discrepancyAmount)}`
                          : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
            <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
            <p className="text-xs text-slate-500">{rows.length} shift{rows.length !== 1 ? 's' : ''} in range.</p>
          </div>
        )
      )}

      {/* Print: full dataset */}
      {data && rows.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Shifts Report" from={query.from} to={query.to} />
          <table className="w-full text-sm border border-slate-100">
            <thead>
              <tr className="bg-slate-50 text-xs text-slate-500">
                <th scope="col" className="text-left px-4 py-2.5 font-medium">Date</th>
                <th scope="col" className="text-left px-4 py-2.5 font-medium">Window</th>
                <th scope="col" className="text-left px-4 py-2.5 font-medium">Status</th>
                <th scope="col" className="text-right px-4 py-2.5 font-medium">Amount Due</th>
                <th scope="col" className="text-right px-4 py-2.5 font-medium">Cash</th>
                <th scope="col" className="text-right px-4 py-2.5 font-medium">UPI</th>
                <th scope="col" className="text-right px-4 py-2.5 font-medium">Card</th>
                <th scope="col" className="text-right px-4 py-2.5 font-medium">Credit</th>
                <th scope="col" className="text-right px-4 py-2.5 font-medium">Discrepancy</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(s => (
                <tr key={s.id} className="border-t border-slate-100">
                  <td className="px-4 py-2 text-slate-600">{s.shiftDate}</td>
                  <td className="px-4 py-2 text-slate-500">{s.shiftName ?? '—'}</td>
                  <td className="px-4 py-2 text-slate-500 text-xs">{codeLabel(s.status)}</td>
                  <td className="px-4 py-2 text-right font-medium text-slate-700">{fmtAmt(s.totalAmountDue)}</td>
                  <td className="px-4 py-2 text-right text-slate-600">{fmtAmt(s.cashCollected)}</td>
                  <td className="px-4 py-2 text-right text-slate-600">{fmtAmt(s.upiCollected)}</td>
                  <td className="px-4 py-2 text-right text-slate-600">{fmtAmt(s.cardCollected)}</td>
                  <td className="px-4 py-2 text-right text-orange-600">{fmtAmt(s.creditTotal)}</td>
                  <td className={`px-4 py-2 text-right text-xs font-semibold ${
                    s.discrepancyAmount && s.discrepancyAmount > 0
                      ? (s.discrepancyType === 'SHORT' ? 'text-red-600' : 'text-amber-600')
                      : 'text-slate-500'
                  }`}>
                    {s.discrepancyAmount && s.discrepancyAmount > 0
                      ? `${s.discrepancyType} ${fmtAmt(s.discrepancyAmount)}`
                      : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-xs text-slate-500">{rows.length} shift{rows.length !== 1 ? 's' : ''} in range.</p>
        </div>
      )}
    </div>
  )
}
