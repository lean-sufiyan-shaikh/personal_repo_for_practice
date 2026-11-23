import { useState } from 'react'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { ChevronDown, ChevronUp } from 'lucide-react'
import { reportApi } from '../../../api/reportApi'
import { pumpApi } from '../../../api/pumpApi'
import type { InventoryLotsReport, AllTanksInventoryReport, InventoryLotLine, ConsumptionLine, CrossTankSummaryLine } from '../../../api/reportApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { Pagination } from '../../../components/Pagination'
import { pageSlice } from '../../../utils/pageSlice'
import { DateRangeInputs, ErrorBox, PrintHeader } from '../shared'
import { localDateInputValue } from '../../../utils/date'
import { useReportRange } from '../useReportRange'
import { fmtQty, fmtShortDate, fmtShortDayMonth } from '../reportFormat'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { MoneyText } from '../../../components/AmountText'
import { codeLabel } from '../../../constants/labels'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import type { DateRange } from '../../../utils/datePresets'

const ALL_TANKS_DEFAULT_DAYS = 90

function LotBadges({ lot, dipLabel = 'DIP Adj.' }: { lot: InventoryLotLine; dipLabel?: string }) {
  return (
    <>
      <StatusBadge tone={lot.status === 'ACTIVE' ? 'ok' : 'neutral'} label={codeLabel(lot.status)} />
      {lot.isDipAdjustment && <StatusBadge tone="warn" label={dipLabel} className="ml-1" />}
    </>
  )
}

function consumptionColumns(lot: InventoryLotLine): DataTableColumn<ConsumptionLine>[] {
  return [
    { key: 'reference', header: 'Tanker Ref', cell: () => <span className="text-ink-2 font-medium">{lot.tankerReference}</span> },
    { key: 'date', header: 'Date', cell: (c) => <span className="text-ink-3">{fmtShortDayMonth(c.consumedAt)}</span> },
    { key: 'source', header: 'Source', cell: (c) => <span className="text-ink-3">{codeLabel(c.sourceType)}</span> },
    { key: 'shift', header: 'Shift', cell: (c) => <span className="text-ink-3">{c.shiftName ?? '—'}</span> },
    {
      key: 'quantity', header: 'Qty (L)', numeric: true,
      cell: (c) => <span className="text-ink">{c.quantityConsumed.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</span>,
    },
    { key: 'cost', header: 'Cost/L', numeric: true, cell: (c) => <MoneyText value={c.costPricePerUnit} className="text-ink-2" /> },
    { key: 'total', header: 'Total', numeric: true, cell: (c) => <MoneyText value={c.totalCost} className="font-medium text-ink" /> },
  ]
}

const TANK_LOT_COLUMNS: DataTableColumn<InventoryLotLine>[] = [
  { key: 'reference', header: 'Tanker Ref', cell: (lot) => <span className="text-ink-2">{lot.tankerReference}</span> },
  { key: 'delivered', header: 'Delivery Date', cell: (lot) => <span className="text-ink-3">{fmtShortDate(lot.deliveryDate)}</span> },
  { key: 'cost', header: 'Cost/L', numeric: true, cell: (lot) => <MoneyText value={lot.costPricePerUnit} className="text-ink-2" /> },
  { key: 'original', header: 'Original', numeric: true, cell: (lot) => <span className="text-ink-3">{fmtQty(lot.originalQuantity)}</span> },
  { key: 'remaining', header: 'Remaining', numeric: true, cell: (lot) => <span className="font-medium text-ink">{fmtQty(lot.remainingQuantity)}</span> },
  { key: 'status', header: 'Status', cell: (lot) => <LotBadges lot={lot} dipLabel="DIP" /> },
]

const CROSS_TANK_COLUMNS: DataTableColumn<CrossTankSummaryLine>[] = [
  { key: 'fuel', header: 'Fuel Type', cell: (row) => <span className="font-medium text-ink">{codeLabel(row.fuelType)}</span> },
  { key: 'remaining', header: 'Total Remaining', numeric: true, cell: (row) => <span className="text-ink-2">{fmtQty(row.totalRemaining)}</span> },
  {
    key: 'avgCost', header: 'Weighted Avg Cost', numeric: true,
    cell: (row) => <span className="text-ink-2">{row.weightedAvgCostPerUnit != null ? `₹${row.weightedAvgCostPerUnit.toFixed(2)}/L` : '—'}</span>,
  },
  { key: 'value', header: 'Total Value', numeric: true, cell: (row) => <MoneyText value={row.totalStockValue} className="font-semibold text-ink" /> },
]

const CSV_COLUMNS: CsvColumn<InventoryLotLine>[] = [
  { header: 'Lot', value: (row) => row.lotId, kind: 'number' },
  { header: 'Reference', value: (row) => row.tankerReference },
  { header: 'Fuel', value: (row) => codeLabel(row.fuelType) },
  { header: 'Delivery date', value: (row) => row.deliveryDate, kind: 'date' },
  { header: 'Original litres', value: (row) => row.originalQuantity.toFixed(2), kind: 'number' },
  { header: 'Consumed litres', value: (row) => row.totalConsumed.toFixed(2), kind: 'number' },
  { header: 'Remaining litres', value: (row) => row.remainingQuantity.toFixed(2), kind: 'number' },
  { header: 'Cost per litre', value: (row) => row.costPricePerUnit, kind: 'money' },
  { header: 'Cost of goods consumed', value: (row) => row.totalCogsConsumed, kind: 'money' },
  { header: 'Status', value: (row) => codeLabel(row.status) },
]

function allTanksDefaultRange() {
  return { from: localDateInputValue(-(ALL_TANKS_DEFAULT_DAYS - 1)), to: localDateInputValue() }
}

// ── Inventory Lots Tab ────────────────────────────────────────────────────────

export function InventoryLotsTab({ pumpId }: { pumpId: number }) {
  const [tankId, setTankId] = useState<string>('')    // '' = All Tanks, '123' = specific tank id
  const [query,  setQuery]  = useState<string>('')    // mirrors tankId on Load click
  const [range, setRange] = useReportRange(allTanksDefaultRange)
  const [from, setFrom] = useState(range.from)
  const [to,   setTo]   = useState(range.to)
  const [expandedLot, setExpandedLot] = useState<number | null>(null)

  const { data: tanks = [] } = useQuery({
    queryKey: ['tanks', pumpId],
    queryFn: () => pumpApi.getTanks(pumpId),
  })

  const [page, setPage]   = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data: singleTankData, isFetching: singleFetching, error: singleError } =
    useQuery<InventoryLotsReport>({
      queryKey: ['report-inventory-single', pumpId, query],
      queryFn:  () => reportApi.getInventoryLots(pumpId, Number(query)),
      enabled:  query !== '' && query !== null,
    })

  const { data: allTanksData, isFetching: allFetching, error: allError } =
    useQuery<AllTanksInventoryReport>({
      queryKey: ['report-inventory-all', pumpId, range.from, range.to],
      queryFn:  () => reportApi.getAllTanksInventoryLots(pumpId, range.from, range.to),
      enabled:  query === '' && !!range.from && !!range.to,
    })

  const isFetching = singleFetching || allFetching
  const error      = singleError ?? allError
  const data       = singleTankData
  const paged      = data ? pageSlice(data.lots, page, pageSize) : null
  const csvRows    = query === '' ? (allTanksData?.tanks.flatMap((tank) => tank.lots) ?? []) : (data?.lots ?? [])

  const applyRange = (next: DateRange) => {
    setFrom(next.from)
    setTo(next.to)
    setQuery('')
    setRange(next)
    setPage(0)
  }

  return (
    <div className="space-y-4">
      <div className="ui-card ui-card-muted p-4 print:hidden">
        <div className="ui-filter-group">
          <div className="w-56">
            <span className="ui-label">Tank</span>
            <SearchableSelect label="Tank"
              value={tankId}
              onChange={(v) => setTankId(v ?? '')}
              options={[
                { value: '', label: 'All Tanks' },
                ...tanks.map(t => ({ value: String(t.id), label: `${t.tankIdentifier} (${t.fuelType})` })),
              ]}
              placeholder="Select tank…"
            />
          </div>
          {tankId === '' && <DateRangeInputs from={from} to={to} onFromChange={setFrom} onToChange={setTo} onPreset={applyRange} />}
          <button
            onClick={() => { setQuery(tankId); setRange({ from, to }); setPage(0) }}
            disabled={isFetching}
            className="ui-btn ui-btn-primary"
          >
            {isFetching ? 'Loading…' : 'Load Lots'}
          </button>
          <button
            onClick={() => window.print()}
            disabled={!data || data.lots.length === 0}
            className="ui-btn ui-btn-secondary"
          >
            Print PDF
          </button>
          <CsvExportButton
            filename={csvFilename(query === '' ? 'inventory-lots' : `inventory-lots-tank-${query}`, query === '' ? range : undefined)}
            columns={CSV_COLUMNS}
            rows={csvRows}
          />
        </div>
      </div>

      {query === '' && (
        <p className="ui-help print:hidden">
          Showing every active lot, plus used-up lots delivered between {fmtShortDate(range.from)} and {fmtShortDate(range.to)}.
        </p>
      )}

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {/* Single-tank view */}
      {data && paged && query !== '' && (
        <div className="space-y-3 print:hidden">
          {/* Summary strip */}
          {data.weightedAvgCostPerUnit != null && (
            <div className="grid grid-cols-3 gap-3">
              <div className="ui-card ui-card-muted p-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-ink-3">Total Remaining</p>
                <p className="text-sm font-semibold text-ink mt-0.5">{fmtQty(data.totalRemaining)}</p>
              </div>
              <div className="ui-card ui-card-muted p-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-ink-3">Avg Cost</p>
                <p className="text-sm font-semibold text-ink mt-0.5">₹{data.weightedAvgCostPerUnit.toFixed(2)}/L</p>
              </div>
              <div className="ui-card ui-card-muted p-3">
                <p className="text-xs font-semibold uppercase tracking-wide text-ink-3">Stock Value</p>
                <MoneyText value={data.totalStockValue} className="block text-sm font-semibold text-ink mt-0.5" />
              </div>
            </div>
          )}

          <p className="text-xs text-ink-3">{data.totalLots} lot{data.totalLots !== 1 ? 's' : ''} in this tank.</p>
          {paged.content.map((lot) => (
            <div key={lot.lotId} className="ui-card p-0 overflow-hidden">
              <button
                className="ui-accordion-trigger"
                onClick={() => setExpandedLot(expandedLot === lot.lotId ? null : lot.lotId)}
              >
                <div className="flex items-center gap-3">
                  <LotBadges lot={lot} />
                  <span className="text-sm font-medium text-ink">{fmtShortDate(lot.deliveryDate)}</span>
                  <span className="text-xs text-ink-3">{codeLabel(lot.fuelType)}</span>
                  <span className="text-xs text-ink-3">{lot.tankerReference}</span>
                </div>
                <div className="flex items-center gap-4 text-xs text-ink-3">
                  <span>₹{lot.costPricePerUnit}/L</span>
                  <span>{fmtQty(lot.remainingQuantity)} remaining</span>
                  <span><MoneyText value={lot.totalCogsConsumed} /> COGS</span>
                  {expandedLot === lot.lotId
                    ? <ChevronUp size={14} strokeWidth={2} className="text-ink-3" />
                    : <ChevronDown size={14} strokeWidth={2} className="text-ink-3" />
                  }
                </div>
              </button>
              {expandedLot === lot.lotId && lot.consumptions.length > 0 && (
                <DataTable
                  caption={`Consumption of lot ${lot.tankerReference}`}
                  captionHidden
                  columns={consumptionColumns(lot)}
                  rows={lot.consumptions}
                  rowKey={(c) => c.id}
                  className="rounded-none border-x-0 border-b-0"
                  tableClassName="text-xs"
                />
              )}
              {expandedLot === lot.lotId && lot.consumptions.length === 0 && (
                <p className="ui-empty border-t border-border px-4 py-3">No consumptions yet.</p>
              )}
            </div>
          ))}
          {paged.content.length === 0 && (
            <div className="ui-empty py-6">No inventory lots found for this tank.</div>
          )}
          <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
        </div>
      )}

      {/* All-tanks view */}
      {allTanksData && query === '' && (
        <div className="space-y-3 print:hidden">
          {allTanksData.tanks.length === 0 ? (
            <div className="ui-empty py-6">No tanks configured for this pump.</div>
          ) : (
            <>
              {allTanksData.tanks.map((section) => (
                <div key={section.tankId} className="ui-card p-0 overflow-hidden">
                  <button
                    className="ui-accordion-trigger"
                    onClick={() => setExpandedLot(expandedLot === section.tankId ? null : section.tankId)}
                  >
                    <div className="flex items-center gap-3">
                      <span className="text-sm font-semibold text-ink">{section.tankIdentifier}</span>
                      <span className="text-xs text-ink-3">{codeLabel(section.fuelType)}</span>
                      <StatusBadge tone="neutral" label={`${section.lotCount} lots`} />
                    </div>
                    <div className="flex items-center gap-4 text-xs text-ink-3">
                      <span>{fmtQty(section.totalRemaining)} remaining</span>
                      {section.weightedAvgCostPerUnit != null && (
                        <span>₹{section.weightedAvgCostPerUnit.toFixed(2)}/L avg</span>
                      )}
                      <span><MoneyText value={section.totalStockValue} /> value</span>
                      {expandedLot === section.tankId
                        ? <ChevronUp size={14} strokeWidth={2} className="text-ink-3" />
                        : <ChevronDown size={14} strokeWidth={2} className="text-ink-3" />}
                    </div>
                  </button>
                  {expandedLot === section.tankId && (
                    <div className="p-4 border-t border-border">
                      {section.lots.length === 0 ? (
                        <p className="ui-empty">No lots in this tank.</p>
                      ) : (
                        <DataTable
                          caption={`Lots in ${section.tankIdentifier}`}
                          captionHidden
                          columns={TANK_LOT_COLUMNS}
                          rows={section.lots}
                          rowKey={(lot) => lot.lotId}
                          tableClassName="text-xs"
                        />
                      )}
                    </div>
                  )}
                </div>
              ))}

              {/* Cross-tank summary */}
              <DataTable
                caption="Cross-Tank Summary"
                columns={CROSS_TANK_COLUMNS}
                rows={allTanksData.crossTankSummary}
                rowKey={(row) => row.fuelType}
              />
            </>
          )}
        </div>
      )}

      {/* Print: single-tank full dataset */}
      {data && data.lots.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Inventory Lots Report" />
          <p className="text-xs text-ink-3 mb-2">{data.totalLots} lot{data.totalLots !== 1 ? 's' : ''} in this tank.</p>
          {data.lots.map((lot) => (
            <div key={lot.lotId} className="mb-4">
              <div className="flex items-center gap-3 mb-1 pb-1 border-b border-border flex-wrap">
                <LotBadges lot={lot} />
                <span className="text-sm font-medium text-ink">{fmtShortDate(lot.deliveryDate)}</span>
                <span className="text-xs text-ink-3">{codeLabel(lot.fuelType)}</span>
                <span className="text-xs text-ink-3">{lot.tankerReference}</span>
                <span className="text-xs text-ink-3">₹{lot.costPricePerUnit}/L</span>
                <span className="text-xs text-ink-3">{fmtQty(lot.remainingQuantity)} remaining</span>
                <span className="text-xs text-ink-3"><MoneyText value={lot.totalCogsConsumed} /> COGS</span>
              </div>
              {lot.consumptions.length > 0 ? (
                <DataTable
                  caption={`Consumption of lot ${lot.tankerReference}`}
                  captionHidden
                  columns={consumptionColumns(lot)}
                  rows={lot.consumptions}
                  rowKey={(c) => c.id}
                  tableClassName="text-xs"
                />
              ) : (
                <p className="ui-empty px-3 py-2">No consumptions yet.</p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
