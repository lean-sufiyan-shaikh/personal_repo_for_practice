import { useState } from 'react'
import { Link } from 'react-router-dom'
import { withToggled } from '../../../utils/setToggle'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { reportApi } from '../../../api/reportApi'
import type { LotCostLine, ProfitLossDayLine, ProfitLossFuelLine, ProfitLossReport } from '../../../api/reportApi'
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
import { fmtQty, fmtShortDate } from '../reportFormat'
import { ProfitLossDailyChart } from '../charts/ProfitLossDailyChart'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { MoneyText } from '../../../components/AmountText'
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

const DAY_TABLE_COLUMNS: DataTableColumn<ProfitLossDayLine>[] = [
  { key: 'date', header: 'Date', cell: (day) => fmtShortDate(day.date) },
  { key: 'revenue', header: 'Revenue', numeric: true, cell: (day) => <MoneyText value={day.revenue} /> },
  { key: 'cogs', header: 'Cost of goods', numeric: true, cell: (day) => <MoneyText value={day.cogs} /> },
  {
    key: 'profit', header: 'Gross profit', numeric: true,
    cell: (day) => <MoneyText value={day.grossProfit} tone={day.grossProfit >= 0 ? 'ok' : 'bad'} className="font-medium" />,
  },
]

const PRINT_FUEL_TABLE_COLUMNS: DataTableColumn<ProfitLossFuelLine>[] = [
  { key: 'fuel', header: 'Fuel Type' },
  { key: 'revenue', header: 'Revenue', numeric: true },
  { key: 'cogs', header: 'COGS', numeric: true },
  { key: 'profit', header: 'Gross Profit', numeric: true },
  { key: 'margin', header: '₹/L Margin', numeric: true },
]

const FUEL_TABLE_COLUMNS: DataTableColumn<ProfitLossFuelLine>[] = [
  { key: 'expand', header: <span className="sr-only">Details</span>, className: 'w-6 px-2' },
  ...PRINT_FUEL_TABLE_COLUMNS,
  { key: 'shifts', header: <span className="sr-only">Shifts</span>, align: 'right' },
]

const LOT_COST_COLUMNS: DataTableColumn<LotCostLine>[] = [
  { key: 'reference', header: 'Lot Ref', cell: (lot) => <span className="text-ink-2">{lot.tankerReference}</span> },
  { key: 'delivered', header: 'Delivery Date', cell: (lot) => <span className="text-ink-3">{fmtShortDate(lot.deliveryDate)}</span> },
  { key: 'cost', header: 'Cost/L', numeric: true, cell: (lot) => <MoneyText value={lot.costPricePerUnit} className="text-ink-2" /> },
  { key: 'units', header: 'Units Consumed', numeric: true, cell: (lot) => <span className="text-ink-2">{fmtQty(lot.quantityConsumed)}</span> },
  { key: 'total', header: 'Cost', numeric: true, cell: (lot) => <MoneyText value={lot.lotCost} className="font-medium text-ink" /> },
]

function LotCostTable({ fuelType, lines }: { fuelType: string; lines: LotCostLine[] }) {
  return (
    <DataTable
      caption={`Fuel lots used for ${codeLabel(fuelType)}`}
      captionHidden
      columns={LOT_COST_COLUMNS}
      rows={lines}
      rowKey={(lot) => `${lot.lotId}-${lot.costPricePerUnit}`}
      tableClassName="text-xs"
    />
  )
}

function FuelFigureCells({ row }: { row: ProfitLossFuelLine }) {
  return (
    <>
      <td className="font-medium text-ink">{codeLabel(row.fuelType)}</td>
      <td className="text-right"><MoneyText value={row.revenue} className="text-ink" /></td>
      <td className="text-right"><MoneyText value={row.cogs} tone="bad" /></td>
      <td className="text-right"><MoneyText value={row.grossProfit} tone={row.grossProfit >= 0 ? 'ok' : 'bad'} className="font-semibold" /></td>
      <td className="text-right text-ink-3 text-xs">
        {row.grossMarginPerLitre != null ? `₹${row.grossMarginPerLitre.toFixed(2)}/L` : '—'}
      </td>
    </>
  )
}

function RevenueBreakdown({ report, className }: { report: ProfitLossReport; className: string }) {
  const figures = [
    { label: 'Cash', value: report.totalCashRevenue, tone: undefined },
    { label: 'UPI', value: report.totalUpiRevenue, tone: undefined },
    { label: 'Card', value: report.totalCardRevenue, tone: undefined },
    { label: 'Credit Sales', value: report.totalCreditRevenue, tone: 'warn' as const },
  ]
  return (
    <div className={`grid grid-cols-4 gap-3 ${className}`}>
      {figures.map((figure) => (
        <div key={figure.label}>
          <p className="text-xs font-semibold uppercase tracking-wide text-ink-3">{figure.label}</p>
          <MoneyText value={figure.value} tone={figure.tone} className={`mt-0.5 block text-sm font-semibold ${figure.tone ? '' : 'text-ink'}`} />
        </div>
      ))}
    </div>
  )
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
            <SummaryCard label="Total Revenue" value={<MoneyText value={data.totalRevenue} />} color="text-ink"
              note={previous && <PeriodChangeNote current={data.totalRevenue} previous={previous.totalRevenue} />} />
            <SummaryCard label="Cost of Goods" value={<MoneyText value={data.totalCogs} />}    color="text-bad"
              note={previous && <PeriodChangeNote current={data.totalCogs} previous={previous.totalCogs} higherIsBetter={false} />} />
            <SummaryCard label="Gross Profit"  value={<MoneyText value={data.grossProfit} />}  color={data.grossProfit >= 0 ? 'text-ok' : 'text-bad'}
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
                <DataTable
                  caption="Revenue, cost of goods and gross profit by day"
                  captionHidden
                  regionLabel="Daily figures"
                  columns={DAY_TABLE_COLUMNS}
                  rows={data.byDay}
                  rowKey={(day) => day.date}
                  scroll
                  className="mt-3"
                />
              </details>
            </>
          )}

          {/* Revenue breakdown toggle */}
          <div className="ui-card ui-card-muted p-3">
            <button
              onClick={() => setShowRevBreakdown(v => !v)}
              className="flex items-center gap-2 text-sm font-medium text-primary hover:text-primary-strong"
            >
              {showRevBreakdown
                ? <ChevronUp size={14} strokeWidth={2} />
                : <ChevronDown size={14} strokeWidth={2} />}
              Revenue breakdown (Cash / UPI / Card / Credit)
            </button>
            {showRevBreakdown && <RevenueBreakdown report={data} className="mt-3" />}
          </div>

          {/* Per-fuel table with drilldown */}
          <DataTable
            caption="Profit and loss by fuel"
            captionHidden
            columns={FUEL_TABLE_COLUMNS}
            rows={data.byFuelType}
            rowKey={(row) => row.fuelType}
            renderRow={(row) => (
              <>
                <tr className="cursor-pointer" onClick={() => toggleFuelRow(row.fuelType)}>
                  <td className="px-2 text-ink-3">
                    <button
                      type="button"
                      aria-expanded={expandedFuelRows.has(row.fuelType)}
                      aria-label={`Show shift details for ${codeLabel(row.fuelType)}`}
                      onClick={(e) => { e.stopPropagation(); toggleFuelRow(row.fuelType) }}
                      className="inline-flex h-8 w-8 items-center justify-center rounded-control"
                    >
                      {expandedFuelRows.has(row.fuelType)
                        ? <ChevronUp size={13} strokeWidth={2} aria-hidden="true" />
                        : <ChevronDown size={13} strokeWidth={2} aria-hidden="true" />}
                    </button>
                  </td>
                  <FuelFigureCells row={row} />
                  <td className="text-right text-xs">
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
                  <tr className="bg-info-bg">
                    <td />
                    <td colSpan={6} className="pt-1">
                      <LotCostTable fuelType={row.fuelType} lines={row.lotCostLines} />
                    </td>
                  </tr>
                )}
              </>
            )}
          />
          <p className="text-xs text-ink-3 mt-1">
            Gross Profit reflects fuel sales only. Operating expenses (salaries, maintenance) are excluded.
          </p>
          <p className="text-xs text-ink-3">{data.totalShifts} closed shift{data.totalShifts !== 1 ? 's' : ''} in range.</p>
        </div>
      )}

      {/* Print: full dataset */}
      {data && data.byFuelType.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Profit & Loss Report" from={query.from} to={query.to} />
          <div className="grid grid-cols-3 gap-4">
            <SummaryCard label="Total Revenue" value={<MoneyText value={data.totalRevenue} />} color="text-ink" />
            <SummaryCard label="Cost of Goods" value={<MoneyText value={data.totalCogs} />}    color="text-bad" />
            <SummaryCard label="Gross Profit"  value={<MoneyText value={data.grossProfit} />}  color={data.grossProfit >= 0 ? 'text-ok' : 'text-bad'} />
          </div>
          {/* Revenue breakdown — always expanded in print */}
          <RevenueBreakdown report={data} className="mb-2" />
          <DataTable
            caption="Profit and loss by fuel, printed"
            captionHidden
            columns={PRINT_FUEL_TABLE_COLUMNS}
            rows={data.byFuelType}
            rowKey={(row) => row.fuelType}
            renderRow={(row) => (
              <>
                <tr>
                  <FuelFigureCells row={row} />
                </tr>
                {row.lotCostLines.length > 0 && (
                  <tr>
                    <td colSpan={5} className="pb-2">
                      <LotCostTable fuelType={row.fuelType} lines={row.lotCostLines} />
                    </td>
                  </tr>
                )}
              </>
            )}
          />
          <p className="text-xs text-ink-3 mt-1">
            Gross Profit reflects fuel sales only. Operating expenses (salaries, maintenance) are excluded.
          </p>
          <p className="text-xs text-ink-3">{data.totalShifts} closed shift{data.totalShifts !== 1 ? 's' : ''} in range.</p>
        </div>
      )}
    </div>
  )
}
