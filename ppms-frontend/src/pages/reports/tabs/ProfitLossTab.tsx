import { Fragment, useState } from 'react'
import { Link } from 'react-router-dom'
import { withToggled } from '../../../utils/setToggle'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { reportApi } from '../../../api/reportApi'
import type { ProfitLossDayLine, ProfitLossFuelLine, ProfitLossReport } from '../../../api/reportApi'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { PeriodChangeNote } from '../../../components/PeriodChangeNote'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import { presetRange, previousPeriod } from '../../../utils/datePresets'
import type { DateRange } from '../../../utils/datePresets'
import { shiftsForFuelUrl } from '../../../utils/drillDown'
import { formatIstDate } from '../../../utils/date'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DateRangeInputs, EmptyRangeSuggestion, ErrorBox, PrintHeader, SummaryCard } from '../shared'
import { fmtAmt, fmtQty, fmtShortDate } from '../reportFormat'
import { ProfitLossDailyChart } from '../charts/ProfitLossDailyChart'
import { ScrollRegion } from '../../../components/ScrollRegion'
import { codeLabel } from '../../../constants/labels'

const FUEL_COLUMNS: CsvColumn<ProfitLossFuelLine>[] = [
  { header: 'Fuel type', value: (row) => codeLabel(row.fuelType) },
  { header: 'Revenue', value: (row) => row.revenue, kind: 'money' },
  { header: 'Cost of goods', value: (row) => row.cogs, kind: 'money' },
  { header: 'Gross profit', value: (row) => row.grossProfit, kind: 'money' },
  { header: 'Margin per litre', value: (row) => row.grossMarginPerLitre, kind: 'money' },
]

const DAY_COLUMNS: CsvColumn<ProfitLossDayLine>[] = [
  { header: 'Date', value: (row) => row.date, kind: 'date' },
  { header: 'Revenue', value: (row) => row.revenue, kind: 'money' },
  { header: 'Cost of goods', value: (row) => row.cogs, kind: 'money' },
  { header: 'Gross profit', value: (row) => row.grossProfit, kind: 'money' },
]

function rangeLabel(range: DateRange): string {
  return `${formatIstDate(range.from)} to ${formatIstDate(range.to)}`
}

// ── P&L Tab ───────────────────────────────────────────────────────────────────

export function ProfitLossTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery, isDefaultRange] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)
  const [showRevBreakdown, setShowRevBreakdown] = useState(false)
  const [expandedFuelRows, setExpandedFuelRows] = useState<Set<string>>(new Set())

  function toggleFuelRow(fuelType: string) {
    setExpandedFuelRows(prev => withToggled(prev, fuelType))
  }

  const { data, isFetching, error } = useQuery<ProfitLossReport>({
    queryKey: ['report-pl', pumpId, query.from, query.to],
    queryFn: () => reportApi.getProfitLoss(pumpId, query.from, query.to),
  })

  const previousRange = previousPeriod(query)
  const { data: previous } = useQuery<ProfitLossReport>({
    queryKey: ['report-pl', pumpId, previousRange.from, previousRange.to],
    queryFn: () => reportApi.getProfitLoss(pumpId, previousRange.from, previousRange.to),
    enabled: !!data,
  })

  const applyRange = (range: DateRange) => {
    setFrom(range.from)
    setTo(range.to)
    setQuery(range)
  }

  const showEmptySuggestion = !!data && isDefaultRange && data.totalShifts === 0

  return (
    <div className="space-y-4">
      {/* Screen: controls */}
      <div className="ui-card ui-card-muted p-4 print:hidden">
        <div className="ui-filter-group">
          <DateRangeInputs from={from} to={to} onFromChange={setFrom} onToChange={setTo} onPreset={applyRange} />
          <button
            onClick={() => setQuery({ from, to })}
            disabled={isFetching}
            className="ui-btn ui-btn-primary"
          >
            {isFetching ? 'Loading…' : 'Generate'}
          </button>
          <button
            onClick={() => window.print()}
            disabled={!data || data.byFuelType.length === 0}
            className="ui-btn ui-btn-secondary"
          >
            Print PDF
          </button>
          <CsvExportButton
            filename={csvFilename('pl', query)}
            columns={FUEL_COLUMNS}
            rows={data?.byFuelType ?? []}
          />
        </div>
      </div>

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {showEmptySuggestion && (
        <div className="print:hidden">
          <EmptyRangeSuggestion what="closed shifts" onShowLastMonth={() => applyRange(presetRange('lastMonth'))} />
        </div>
      )}

      {/* Screen: data */}
      {data && !showEmptySuggestion && (
        <div className="space-y-4 print:hidden">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <SummaryCard label="Total Revenue" value={fmtAmt(data.totalRevenue)} color="text-slate-800"
              note={previous && <PeriodChangeNote current={data.totalRevenue} previous={previous.totalRevenue} />} />
            <SummaryCard label="Cost of Goods" value={fmtAmt(data.totalCogs)}    color="text-red-600"
              note={previous && <PeriodChangeNote current={data.totalCogs} previous={previous.totalCogs} higherIsBetter={false} />} />
            <SummaryCard label="Gross Profit"  value={fmtAmt(data.grossProfit)}  color={data.grossProfit >= 0 ? 'text-green-700' : 'text-red-700'}
              note={previous && <PeriodChangeNote current={data.grossProfit} previous={previous.grossProfit} />} />
          </div>
          {previous && (
            <p className="text-xs text-ink-3">Compared with {rangeLabel(previousRange)}.</p>
          )}

          {data.byDay.length > 0 && (
            <>
              <ProfitLossDailyChart days={data.byDay} />
              <details className="ui-card ui-card-muted p-3">
                <summary className="cursor-pointer text-sm font-medium text-ink">Daily figures table</summary>
                <div className="mt-3 flex justify-end">
                  <CsvExportButton filename={csvFilename('pl-daily', query)} columns={DAY_COLUMNS} rows={data.byDay} />
                </div>
                <div className="ui-table-wrap ui-table-wrap--scroll mt-3">
                  <table className="w-full text-sm">
                    <caption className="sr-only">Revenue, cost of goods and gross profit by day</caption>
                    <thead>
                      <tr>
                        <th scope="col" className="text-left">Date</th>
                        <th scope="col" className="text-right">Revenue</th>
                        <th scope="col" className="text-right">Cost of goods</th>
                        <th scope="col" className="text-right">Gross profit</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.byDay.map((day) => (
                        <tr key={day.date}>
                          <td>{fmtShortDate(day.date)}</td>
                          <td className="text-right">{fmtAmt(day.revenue)}</td>
                          <td className="text-right">{fmtAmt(day.cogs)}</td>
                          <td className={`text-right font-medium ${day.grossProfit >= 0 ? 'text-ok' : 'text-bad'}`}>{fmtAmt(day.grossProfit)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            </>
          )}

          {/* Revenue breakdown toggle */}
          <div className="ui-card ui-card-muted p-3">
            <button
              onClick={() => setShowRevBreakdown(v => !v)}
              className="flex items-center gap-2 text-sm font-medium text-blue-600 hover:text-blue-700"
            >
              {showRevBreakdown
                ? <ChevronUp size={14} strokeWidth={2} />
                : <ChevronDown size={14} strokeWidth={2} />}
              Revenue breakdown (Cash / UPI / Card / Credit)
            </button>
            {showRevBreakdown && (
              <div className="grid grid-cols-4 gap-3 mt-3">
                <div>
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Cash</p>
                  <p className="text-sm font-semibold text-slate-700 mt-0.5">{fmtAmt(data.totalCashRevenue)}</p>
                </div>
                <div>
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">UPI</p>
                  <p className="text-sm font-semibold text-slate-700 mt-0.5">{fmtAmt(data.totalUpiRevenue)}</p>
                </div>
                <div>
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Card</p>
                  <p className="text-sm font-semibold text-slate-700 mt-0.5">{fmtAmt(data.totalCardRevenue)}</p>
                </div>
                <div>
                  <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">Credit Sales</p>
                  <p className="text-sm font-semibold text-orange-600 mt-0.5">{fmtAmt(data.totalCreditRevenue)}</p>
                </div>
              </div>
            )}
          </div>

          {/* Per-fuel table with drilldown */}
          <div className="ui-table-wrap">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 text-xs text-slate-500">
                  <th scope="col" className="w-6 px-2 py-2.5" />
                  <th scope="col" className="text-left px-4 py-2.5 font-medium">Fuel Type</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">Revenue</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">COGS</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">Gross Profit</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">₹/L Margin</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium"><span className="sr-only">Shifts</span></th>
                </tr>
              </thead>
              <tbody>
                {data.byFuelType.map((row) => (
                  <Fragment key={row.fuelType}>
                    <tr
                      className="border-t border-slate-100 cursor-pointer hover:bg-slate-50/60"
                      onClick={() => toggleFuelRow(row.fuelType)}
                    >
                      <td className="px-2 py-2.5 text-slate-500">
                        <button
                          type="button"
                          aria-expanded={expandedFuelRows.has(row.fuelType)}
                          aria-label={`Show shift details for ${codeLabel(row.fuelType)}`}
                          onClick={(e) => { e.stopPropagation(); toggleFuelRow(row.fuelType) }}
                          className="inline-flex h-8 w-8 items-center justify-center rounded-md"
                        >
                          {expandedFuelRows.has(row.fuelType)
                            ? <ChevronUp size={13} strokeWidth={2} aria-hidden="true" />
                            : <ChevronDown size={13} strokeWidth={2} aria-hidden="true" />}
                        </button>
                      </td>
                      <td className="px-4 py-2.5 font-medium text-slate-700">{codeLabel(row.fuelType)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 tabular-nums">{fmtAmt(row.revenue)}</td>
                      <td className="px-4 py-2.5 text-right text-red-600 tabular-nums">{fmtAmt(row.cogs)}</td>
                      <td className={`px-4 py-2.5 text-right font-semibold tabular-nums ${row.grossProfit >= 0 ? 'text-green-700' : 'text-red-700'}`}>
                        {fmtAmt(row.grossProfit)}
                      </td>
                      <td className="px-4 py-2.5 text-right text-slate-500 text-xs tabular-nums">
                        {row.grossMarginPerLitre != null ? `₹${row.grossMarginPerLitre.toFixed(2)}/L` : '—'}
                      </td>
                      <td className="px-4 py-2.5 text-right text-xs">
                        <Link
                          to={shiftsForFuelUrl(query, row.fuelType)}
                          className="ui-link"
                          onClick={(e) => e.stopPropagation()}
                          aria-label={`View ${codeLabel(row.fuelType)} shifts from ${rangeLabel(query)}`}
                        >
                          View shifts
                        </Link>
                      </td>
                    </tr>
                    {expandedFuelRows.has(row.fuelType) && row.lotCostLines.length > 0 && (
                      <tr key={`${row.fuelType}-lots`} className="bg-blue-50/40">
                        <td />
                        <td colSpan={6} className="px-4 pb-3 pt-1">
                          <table className="w-full text-xs border-collapse">
                            <thead>
                              <tr className="text-slate-500 font-semibold">
                                <th scope="col" className="text-left py-1.5 pr-3">Lot Ref</th>
                                <th scope="col" className="text-left py-1.5 pr-3">Delivery Date</th>
                                <th scope="col" className="text-right py-1.5 pr-3">Cost/L</th>
                                <th scope="col" className="text-right py-1.5 pr-3">Units Consumed</th>
                                <th scope="col" className="text-right py-1.5">Cost</th>
                              </tr>
                            </thead>
                            <tbody>
                              {row.lotCostLines.map((lot) => (
                                <tr key={`${lot.lotId}-${lot.costPricePerUnit}`} className="border-t border-blue-100">
                                  <td className="py-1.5 pr-3 text-slate-600">{lot.tankerReference}</td>
                                  <td className="py-1.5 pr-3 text-slate-500">{fmtShortDate(lot.deliveryDate)}</td>
                                  <td className="py-1.5 pr-3 text-right text-slate-600">₹{lot.costPricePerUnit.toFixed(2)}</td>
                                  <td className="py-1.5 pr-3 text-right text-slate-600">{fmtQty(lot.quantityConsumed)}</td>
                                  <td className="py-1.5 text-right font-medium text-slate-700">{fmtAmt(lot.lotCost)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-xs text-slate-500 mt-1">
            Gross Profit reflects fuel sales only. Operating expenses (salaries, maintenance) are excluded.
          </p>
          <p className="text-xs text-slate-500">{data.totalShifts} closed shift{data.totalShifts !== 1 ? 's' : ''} in range.</p>
        </div>
      )}

      {/* Print: full dataset */}
      {data && data.byFuelType.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Profit & Loss Report" from={query.from} to={query.to} />
          <div className="grid grid-cols-3 gap-4">
            <SummaryCard label="Total Revenue" value={fmtAmt(data.totalRevenue)} color="text-slate-800" />
            <SummaryCard label="Cost of Goods" value={fmtAmt(data.totalCogs)}    color="text-red-600" />
            <SummaryCard label="Gross Profit"  value={fmtAmt(data.grossProfit)}  color={data.grossProfit >= 0 ? 'text-green-700' : 'text-red-700'} />
          </div>
          {/* Revenue breakdown — always expanded in print */}
          <div className="grid grid-cols-4 gap-3 mb-2">
            <div>
              <p className="text-[10px] text-slate-500 uppercase font-semibold">Cash</p>
              <p className="text-sm font-semibold text-slate-700">{fmtAmt(data.totalCashRevenue)}</p>
            </div>
            <div>
              <p className="text-[10px] text-slate-500 uppercase font-semibold">UPI</p>
              <p className="text-sm font-semibold text-slate-700">{fmtAmt(data.totalUpiRevenue)}</p>
            </div>
            <div>
              <p className="text-[10px] text-slate-500 uppercase font-semibold">Card</p>
              <p className="text-sm font-semibold text-slate-700">{fmtAmt(data.totalCardRevenue)}</p>
            </div>
            <div>
              <p className="text-[10px] text-slate-500 uppercase font-semibold">Credit Sales</p>
              <p className="text-sm font-semibold text-orange-600">{fmtAmt(data.totalCreditRevenue)}</p>
            </div>
          </div>
          <ScrollRegion label="Profit and loss by fuel" className="ui-card p-0 overflow-hidden overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 text-xs text-slate-500">
                  <th scope="col" className="text-left px-4 py-2.5 font-medium">Fuel Type</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">Revenue</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">COGS</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">Gross Profit</th>
                  <th scope="col" className="text-right px-4 py-2.5 font-medium">₹/L Margin</th>
                </tr>
              </thead>
              <tbody>
                {data.byFuelType.map((row) => (
                  <Fragment key={row.fuelType}>
                    <tr className="border-t border-slate-100">
                      <td className="px-4 py-2.5 font-medium text-slate-700">{codeLabel(row.fuelType)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-700 tabular-nums">{fmtAmt(row.revenue)}</td>
                      <td className="px-4 py-2.5 text-right text-red-600 tabular-nums">{fmtAmt(row.cogs)}</td>
                      <td className={`px-4 py-2.5 text-right font-semibold tabular-nums ${row.grossProfit >= 0 ? 'text-green-700' : 'text-red-700'}`}>{fmtAmt(row.grossProfit)}</td>
                      <td className="px-4 py-2.5 text-right text-slate-500 text-xs tabular-nums">
                        {row.grossMarginPerLitre != null ? `₹${row.grossMarginPerLitre.toFixed(2)}/L` : '—'}
                      </td>
                    </tr>
                    {row.lotCostLines.length > 0 && (
                      <tr key={`${row.fuelType}-lots`}>
                        <td colSpan={5} className="px-6 pb-2">
                          <table className="w-full text-xs border-collapse">
                            <thead>
                              <tr className="text-slate-500 font-semibold">
                                <th scope="col" className="text-left py-1 pr-3">Lot Ref</th>
                                <th scope="col" className="text-left py-1 pr-3">Delivery Date</th>
                                <th scope="col" className="text-right py-1 pr-3">Cost/L</th>
                                <th scope="col" className="text-right py-1 pr-3">Units</th>
                                <th scope="col" className="text-right py-1">Cost</th>
                              </tr>
                            </thead>
                            <tbody>
                              {row.lotCostLines.map((lot) => (
                                <tr key={`${lot.lotId}-${lot.costPricePerUnit}`} className="border-t border-slate-100">
                                  <td className="py-1 pr-3 text-slate-600">{lot.tankerReference}</td>
                                  <td className="py-1 pr-3 text-slate-500">{fmtShortDate(lot.deliveryDate)}</td>
                                  <td className="py-1 pr-3 text-right text-slate-600">₹{lot.costPricePerUnit.toFixed(2)}</td>
                                  <td className="py-1 pr-3 text-right text-slate-600">{fmtQty(lot.quantityConsumed)}</td>
                                  <td className="py-1 text-right font-medium text-slate-700">{fmtAmt(lot.lotCost)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
          <p className="text-xs text-slate-500 mt-1">
            Gross Profit reflects fuel sales only. Operating expenses (salaries, maintenance) are excluded.
          </p>
          <p className="text-xs text-slate-500">{data.totalShifts} closed shift{data.totalShifts !== 1 ? 's' : ''} in range.</p>
        </div>
      )}
    </div>
  )
}
