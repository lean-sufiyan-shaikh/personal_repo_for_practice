import { useState } from 'react'
import { Link } from 'react-router-dom'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { reportApi } from '../../../api/reportApi'
import type { DiscrepancyLine, OperatorDiscrepancyReport } from '../../../api/reportApi'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { Pagination } from '../../../components/Pagination'
import { PeriodChangeNote } from '../../../components/PeriodChangeNote'
import { pageSlice } from '../../../utils/pageSlice'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import { presetRange, previousPeriod } from '../../../utils/datePresets'
import type { DateRange } from '../../../utils/datePresets'
import { discrepanciesUrl, shiftUrl } from '../../../utils/drillDown'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DateRangeInputs, EmptyRangeSuggestion, ErrorBox, PrintHeader, SummaryCard } from '../shared'
import { fmtAmt } from '../reportFormat'
import { codeLabel } from '../../../constants/labels'
import { ShortOverChart } from '../charts/ShortOverChart'

type DiscrepancyCsvRow = DiscrepancyLine & { operatorName: string }

const CSV_COLUMNS: CsvColumn<DiscrepancyCsvRow>[] = [
  { header: 'Operator', value: (row) => row.operatorName },
  { header: 'Shift', value: (row) => row.shiftId, kind: 'number' },
  { header: 'Date', value: (row) => row.shiftDate, kind: 'date' },
  { header: 'Type', value: (row) => row.discrepancyType },
  { header: 'Amount', value: (row) => row.discrepancyAmount, kind: 'money' },
  { header: 'Status', value: (row) => codeLabel(row.status) },
  { header: 'Resolution', value: (row) => codeLabel(row.resolution) },
  { header: 'Reason', value: (row) => row.discrepancyReason },
]

function totals(report: OperatorDiscrepancyReport) {
  return {
    short: report.operators.reduce((sum, op) => sum + op.totalShortAmount, 0),
    over: report.operators.reduce((sum, op) => sum + op.totalOverAmount, 0),
  }
}

// ── Short / Over Discrepancy Tab ──────────────────────────────────────────────

export function DiscrepancyTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery, isDefaultRange] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)
  const [expandedOp, setExpandedOp] = useState<number | null>(null)

  const [page, setPage]   = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data, isFetching, error } = useQuery<OperatorDiscrepancyReport>({
    queryKey: ['report-discrepancy', pumpId, query.from, query.to],
    queryFn: () => reportApi.getOperatorDiscrepancy(pumpId, query.from, query.to),
  })

  const paged = data ? pageSlice(data.operators, page, pageSize) : null

  const previousRange = previousPeriod(query)
  const { data: previous } = useQuery<OperatorDiscrepancyReport>({
    queryKey: ['report-discrepancy', pumpId, previousRange.from, previousRange.to],
    queryFn: () => reportApi.getOperatorDiscrepancy(pumpId, previousRange.from, previousRange.to),
    enabled: !!data,
  })

  const applyRange = (range: DateRange) => {
    setFrom(range.from)
    setTo(range.to)
    setQuery(range)
    setPage(0)
  }

  const csvRows: DiscrepancyCsvRow[] = data
    ? data.operators.flatMap((op) => op.shifts.map((line) => ({ ...line, operatorName: op.operatorName })))
    : []
  const current = data ? totals(data) : null
  const before = previous ? totals(previous) : null
  const showEmptySuggestion = !!data && isDefaultRange && data.operators.length === 0

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
          disabled={!data || data.operators.length === 0}
          className="ui-btn ui-btn-secondary"
        >
          Print PDF
        </button>
        <CsvExportButton filename={csvFilename('short-over', query)} columns={CSV_COLUMNS} rows={csvRows} />
        </div>
      </div>

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {showEmptySuggestion && (
        <div className="print:hidden">
          <EmptyRangeSuggestion what="short or over shifts" onShowLastMonth={() => applyRange(presetRange('lastMonth'))} />
        </div>
      )}

      {data && paged && current && !showEmptySuggestion && (
        <div className="space-y-3 print:hidden">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <SummaryCard
              label="Total short"
              value={fmtAmt(current.short)}
              color="text-bad"
              note={<>
                {before && <PeriodChangeNote current={current.short} previous={before.short} higherIsBetter={false} />}
                <Link to={discrepanciesUrl(query, 'SHORT')} className="ui-link mt-1 inline-block text-xs">View short shifts</Link>
              </>}
            />
            <SummaryCard
              label="Total over"
              value={fmtAmt(current.over)}
              color="text-warn"
              note={<>
                {before && <PeriodChangeNote current={current.over} previous={before.over} higherIsBetter={false} />}
                <Link to={discrepanciesUrl(query, 'OVER')} className="ui-link mt-1 inline-block text-xs">View over shifts</Link>
              </>}
            />
          </div>
          {data.operators.length > 0 && <ShortOverChart operators={data.operators} />}
          <p className="text-xs text-slate-500">
            {data.totalDiscrepancyShifts} discrepancy shift{data.totalDiscrepancyShifts !== 1 ? 's' : ''} in range.
          </p>
          {paged.content.length === 0 ? (
            <div className="ui-empty py-6">No discrepancies in this date range.</div>
          ) : (
            paged.content.map((op) => (
              <div key={op.operatorId} className="ui-card p-0 overflow-hidden">
                <button
                  className="ui-accordion-trigger"
                  onClick={() => setExpandedOp(expandedOp === op.operatorId ? null : op.operatorId)}
                >
                  <div className="flex items-center gap-3">
                    <span className="text-sm font-semibold text-slate-700">{op.operatorName}</span>
                    <span className="text-xs text-slate-500">{op.discrepancyShiftCount} shifts</span>
                    {op.unresolvedCount > 0 && (
                      <span className="text-xs bg-red-100 text-red-700 px-2 py-0.5 rounded-full font-medium">
                        {op.unresolvedCount} unresolved
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-4 text-xs">
                    {op.totalShortAmount > 0 && (
                      <span className="text-red-600 font-semibold">Short {fmtAmt(op.totalShortAmount)}</span>
                    )}
                    {op.totalOverAmount > 0 && (
                      <span className="text-amber-600 font-semibold">Over {fmtAmt(op.totalOverAmount)}</span>
                    )}
                    {expandedOp === op.operatorId
                      ? <ChevronUp size={14} strokeWidth={2} className="text-slate-500" />
                      : <ChevronDown size={14} strokeWidth={2} className="text-slate-500" />
                    }
                  </div>
                </button>
                {expandedOp === op.operatorId && (
                  <table className="w-full text-xs border-t border-slate-100">
                    <thead>
                      <tr className="bg-slate-50 text-slate-500">
                        <th scope="col" className="text-left px-4 py-2 font-medium">Shift</th>
                        <th scope="col" className="text-left px-4 py-2 font-medium">Date</th>
                        <th scope="col" className="text-left px-4 py-2 font-medium">Type</th>
                        <th scope="col" className="text-right px-4 py-2 font-medium">Amount</th>
                        <th scope="col" className="text-left px-4 py-2 font-medium">Status</th>
                        <th scope="col" className="text-left px-4 py-2 font-medium">Resolution</th>
                      </tr>
                    </thead>
                    <tbody>
                      {op.shifts.map((s) => (
                        <tr key={s.shiftId} className="border-t border-slate-100">
                          <td className="px-4 py-2">
                            <Link to={shiftUrl(s.shiftId)} className="ui-link">Shift #{s.shiftId}</Link>
                          </td>
                          <td className="px-4 py-2 text-slate-600">{s.shiftDate}</td>
                          <td className={`px-4 py-2 font-semibold ${s.discrepancyType === 'SHORT' ? 'text-red-600' : 'text-amber-600'}`}>
                            {s.discrepancyType}
                          </td>
                          <td className="px-4 py-2 text-right font-semibold text-slate-700">{fmtAmt(s.discrepancyAmount)}</td>
                          <td className="px-4 py-2 text-slate-500">{codeLabel(s.status)}</td>
                          <td className="px-4 py-2 text-slate-500">{codeLabel(s.resolution)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            ))
          )}
          <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
        </div>
      )}

      {/* Print: full dataset */}
      {data && data.operators.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Short / Over Discrepancy Report" from={query.from} to={query.to} />
          <p className="text-xs text-slate-500 mb-2">
            {data.totalDiscrepancyShifts} discrepancy shift{data.totalDiscrepancyShifts !== 1 ? 's' : ''} in range.
          </p>
          {data.operators.map((op) => (
            <div key={op.operatorId} className="mb-4">
              <div className="flex items-center gap-3 mb-1 pb-1 border-b border-slate-200">
                <span className="text-sm font-semibold text-slate-700">{op.operatorName}</span>
                <span className="text-xs text-slate-500">{op.discrepancyShiftCount} shifts</span>
                {op.totalShortAmount > 0 && <span className="text-xs text-red-600 font-semibold">Short {fmtAmt(op.totalShortAmount)}</span>}
                {op.totalOverAmount > 0  && <span className="text-xs text-amber-600 font-semibold">Over {fmtAmt(op.totalOverAmount)}</span>}
              </div>
              <table className="w-full text-xs border border-slate-100">
                <thead>
                  <tr className="bg-slate-50 text-slate-500">
                    <th scope="col" className="text-left px-3 py-2 font-medium">Date</th>
                    <th scope="col" className="text-left px-3 py-2 font-medium">Type</th>
                    <th scope="col" className="text-right px-3 py-2 font-medium">Amount</th>
                    <th scope="col" className="text-left px-3 py-2 font-medium">Status</th>
                    <th scope="col" className="text-left px-3 py-2 font-medium">Resolution</th>
                  </tr>
                </thead>
                <tbody>
                  {op.shifts.map((s) => (
                    <tr key={s.shiftId} className="border-t border-slate-100">
                      <td className="px-3 py-1.5 text-slate-600">{s.shiftDate}</td>
                      <td className={`px-3 py-1.5 font-semibold ${s.discrepancyType === 'SHORT' ? 'text-red-600' : 'text-amber-600'}`}>{s.discrepancyType}</td>
                      <td className="px-3 py-1.5 text-right font-semibold text-slate-700">{fmtAmt(s.discrepancyAmount)}</td>
                      <td className="px-3 py-1.5 text-slate-500">{codeLabel(s.status)}</td>
                      <td className="px-3 py-1.5 text-slate-500">{codeLabel(s.resolution)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
