import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { balanceSheetApi } from '../../api/balanceSheetApi'
import { parseApiError } from '../../utils/apiError'
import { useToastStore } from '../../store/toastStore'
import type { BalanceSheetSummary, BsFuelLine, BsShiftLine } from '../../api/balanceSheetApi'
import { SkeletonRows } from '../../components/Skeleton'
import { IconButton } from '../../components/IconButton'
import { DiscrepancyTd, FuelLineRow, ShiftLineRow, SummaryTile, Td } from './bsTableCells'
import { ExpensesSection } from './sections/ExpensesSection'
import { bsColumn, fmtDate, fmtDateTime } from './bsFormat'
import { ProductSalesSection } from './sections/ProductSalesSection'
import { SettlementSection } from './sections/SettlementSection'
import { DipPlSection } from './sections/DipPlSection'
import { MeterAmendmentsSection } from './sections/MeterAmendmentsSection'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { LitresText, MoneyText } from '../../components/AmountText'
import { StatusBadge } from '../../components/StatusBadge'
import { Link } from 'react-router-dom'
import { CsvExportButton } from '../../components/CsvExportButton'
import { csvFilename } from '../../utils/csv'
import type { CsvColumn } from '../../utils/csv'
import { discrepanciesUrl, shiftsForFuelUrl } from '../../utils/drillDown'

const FUEL_COLUMNS: CsvColumn<BsFuelLine>[] = [
  { header: 'Fuel', value: (row) => row.fuelType },
  { header: 'Opening stock litres', value: (row) => row.openingStock.toFixed(2), kind: 'number' },
  { header: 'Delivered litres', value: (row) => row.deliveredLitres.toFixed(2), kind: 'number' },
  { header: 'Sold litres', value: (row) => row.soldLitres.toFixed(2), kind: 'number' },
  { header: 'Closing stock litres', value: (row) => row.closingStock.toFixed(2), kind: 'number' },
  { header: 'Selling price', value: (row) => row.sellingPrice, kind: 'money' },
  { header: 'Revenue', value: (row) => row.expectedRevenue, kind: 'money' },
  { header: 'Stock variance litres', value: (row) => row.stockVariance.toFixed(2), kind: 'number' },
  { header: 'Credit sold', value: (row) => row.creditSoldAmount, kind: 'money' },
]

const FUEL_PROFIT_COLUMNS: CsvColumn<BsFuelLine>[] = [
  { header: 'Cost of goods', value: (row) => row.costOfGoods, kind: 'money' },
  { header: 'Gross profit', value: (row) => row.grossProfit, kind: 'money' },
  { header: 'Dip loss', value: (row) => row.dipLossAmount, kind: 'money' },
]

const SHIFT_COLUMNS: CsvColumn<BsShiftLine>[] = [
  { header: 'Shift', value: (row) => row.shiftId, kind: 'number' },
  { header: 'Operator', value: (row) => row.operatorName },
  { header: 'DU', value: (row) => row.duNumber, kind: 'number' },
  { header: 'Fuels', value: (row) => row.fuelTypesSummary },
  { header: 'Litres', value: (row) => row.litresSold.toFixed(2), kind: 'number' },
  { header: 'Expected', value: (row) => row.expectedRevenue, kind: 'money' },
  { header: 'Cash', value: (row) => row.cashCollected, kind: 'money' },
  { header: 'UPI', value: (row) => row.upiCollected, kind: 'money' },
  { header: 'Card', value: (row) => row.cardCollected, kind: 'money' },
  { header: 'Fleet card', value: (row) => row.fleetCardCollected ?? 0, kind: 'money' },
  { header: 'Credit', value: (row) => row.creditAmount, kind: 'money' },
  { header: 'Discrepancy', value: (row) => row.discrepancy, kind: 'money' },
]

const SHIFT_TABLE_COLUMNS: DataTableColumn<BsShiftLine>[] = [
  bsColumn('operator', 'Operator'),
  bsColumn('du', 'DU / Nozzle'),
  bsColumn('fuels', 'Fuels'),
  bsColumn('litres', 'Litres', true),
  bsColumn('expected', 'Expected', true),
  bsColumn('received', 'Received', true),
  bsColumn('cash', 'Cash', true),
  bsColumn('upi', 'UPI', true),
  bsColumn('card', 'Card', true),
  bsColumn('fleet', 'Fleet', true),
  bsColumn('credit', 'Credit', true),
  bsColumn('discrepancy', 'Discrepancy', true),
]

function fuelTableColumns(isOwnerOrAdmin: boolean): DataTableColumn<BsFuelLine>[] {
  return [
    bsColumn('fuel', 'Fuel'),
    bsColumn('opening', 'Opening Stock', true),
    bsColumn('delivered', 'Delivered', true),
    bsColumn('sold', 'Sold', true),
    bsColumn('closing', 'Closing Stock', true),
    bsColumn('price', 'Selling Price', true),
    bsColumn('revenue', 'Revenue', true),
    ...(isOwnerOrAdmin ? [bsColumn<BsFuelLine>('cogs', 'COGS', true), bsColumn<BsFuelLine>('profit', 'Profit', true)] : []),
    bsColumn('variance', 'Stock Variance', true),
    ...(isOwnerOrAdmin ? [bsColumn<BsFuelLine>('dipLoss', 'Dip Loss', true)] : []),
    bsColumn('credit', 'Credit Sold', true),
  ]
}

// ── Detail Panel ────────────────────────────────────────────────────────────────

interface DetailPanelProps {
  pumpId: number
  reportId: number
  onClose: () => void
  canDelete: boolean
  onDelete: (summary: BalanceSheetSummary) => void
  onRegenerated: (newReportId: number) => void
  summary: BalanceSheetSummary
  pumpName: string
}

export function DetailPanel({ pumpId, reportId, onClose, canDelete, onDelete, onRegenerated, summary, pumpName }: DetailPanelProps) {
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const { data: detail, isLoading, error } = useQuery({
    queryKey: ['balance-sheet-detail', pumpId, reportId],
    queryFn: () => balanceSheetApi.getById(pumpId, reportId),
  })
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const regenerateMutation = useMutation({
    mutationFn: () => balanceSheetApi.regenerate(pumpId, reportId),
    onSuccess: (regenerated) => {
      qc.invalidateQueries({ queryKey: ['balance-sheets', pumpId] })
      addToast('Balance sheet regenerated', 'success')
      onRegenerated(regenerated.id)
    },
    onError: (err) => addToast(parseApiError(err, 'Failed to regenerate balance sheet'), 'error'),
  })

  if (error) {
    return (
      <div className="flex-1 px-6 py-6 space-y-3">
        <div className="ui-alert ui-alert-danger text-sm" role="alert">
          {parseApiError(error, 'Failed to load this balance sheet. Please try again.')}
        </div>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">Back to list</button>
      </div>
    )
  }

  if (isLoading || !detail) {
    return (
      <div className="flex-1 px-6 py-6">
        <SkeletonRows count={6} />
      </div>
    )
  }

  const sheetRange = { from: detail.reportDate, to: detail.reportDate }
  const fuelColumns = isOwnerOrAdmin ? [...FUEL_COLUMNS, ...FUEL_PROFIT_COLUMNS] : FUEL_COLUMNS

  return (
    <div className="flex-1 overflow-y-auto print:overflow-visible">
      {/* Detail header */}
      <div className="sticky top-0 bg-surface border-b border-border px-6 py-4 flex items-center justify-between z-10">
        <div>
          <div className="flex items-center gap-2">
            <StatusBadge tone={detail.reportType === 'DAY' ? 'neutral' : 'info'} label={detail.reportType === 'DAY' ? 'Day End' : 'Shift'} />
            <h2 className="text-base font-semibold text-ink">
              <span className="hidden print:inline">Balance Sheet — </span>{detail.periodLabel}
            </h2>
          </div>
          <p className="text-xs text-ink-3 mt-0.5">
            {fmtDate(detail.reportDate)} · Generated {fmtDateTime(detail.generatedAt)} by {detail.generatedByUserName}
            {pumpName && <span className="hidden print:inline"> · Pump: <span className="font-medium">{pumpName}</span></span>}
          </p>
          {detail.reportType === 'DAY' && detail.includedShiftNames && detail.includedShiftNames.length > 0 && (
            <p className="text-xs text-ink-3 mt-1">
              Shifts included:{' '}
              {detail.includedShiftNames.map(name => (
                <span key={name} className="inline-flex items-center px-1.5 py-0.5 rounded-badge text-xs font-medium bg-surface-sunken text-ink-2 mr-1">
                  {name}
                </span>
              ))}
            </p>
          )}
        </div>
        <div className="flex items-center gap-2 print:hidden">
          <button
            onClick={onClose}
            className="md:hidden text-xs text-primary font-medium border border-info hover:border-primary px-3 py-1.5 rounded-control transition-colors"
          >
            ← Back
          </button>
          {canDelete && (
            <button
              onClick={() => onDelete(summary)}
              className="text-xs text-bad hover:text-bad-strong border border-bad hover:bg-bad-bg px-3 py-1.5 rounded-control transition-colors"
            >
              Delete
            </button>
          )}
          <button
            onClick={() => window.print()}
            className="text-xs text-ink-2 hover:text-ink border border-control hover:border-ink-3 px-3 py-1.5 rounded-control transition-colors"
          >
            Print PDF
          </button>
          <IconButton
            onClick={onClose}
            className="hidden md:block text-ink-3 hover:text-ink-2 text-xl leading-none"
            label="Close details"
            icon={'×'}
          />
        </div>
      </div>

      <div className="px-6 py-5 space-y-6">
        {(detail.stale || detail.linesChangedSinceGenerated) && (
          <div role="alert" className="ui-alert ui-alert-warning text-sm flex flex-wrap items-center justify-between gap-2">
            <span>
              This sheet is out of date:{' '}
              {detail.staleReason ?? 'Expenses, product sales or dip entries for this day changed after the sheet was generated.'}
              {' '}Totals show the values saved at generation; regenerate it to include the change.
            </span>
            {canDelete && (
              <button
                type="button"
                onClick={() => regenerateMutation.mutate()}
                disabled={regenerateMutation.isPending}
                className="ui-btn ui-btn-primary ui-btn-compact print:hidden"
              >
                {regenerateMutation.isPending ? 'Regenerating…' : 'Regenerate'}
              </button>
            )}
          </div>
        )}
        {detail.openShiftCount > 0 && (
          <div className="ui-alert ui-alert-warning text-sm">
            {detail.openShiftCount} shift{detail.openShiftCount !== 1 ? 's' : ''} still open — this sheet covers only closed shifts.
          </div>
        )}

        {/* Notes */}
        {detail.notes && (
          <div className="ui-card-plain ui-card-muted px-4 py-3 text-sm text-ink-2">
            {detail.notes}
          </div>
        )}

        {/* Cash summary tiles */}
        <section>
          <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider mb-3">Cash & Revenue Summary</h3>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <SummaryTile label="Expected Revenue" value={<MoneyText value={detail.totalExpectedRevenue} />} />
            <SummaryTile label="Cash Collected" value={<MoneyText value={detail.totalCashCollected} />} />
            <SummaryTile label="UPI Collected" value={<MoneyText value={detail.totalUpiCollected} />} />
            <SummaryTile label="Card Collected" value={<MoneyText value={detail.totalCardCollected} />} />
            <SummaryTile label="Credit Sold" value={<MoneyText value={detail.totalCreditSold} />} accent="amber" />
            {detail.reportType === 'DAY' && (
              <SummaryTile label="Credit Recovered" value={<MoneyText value={detail.totalCreditRecovered} />} accent="green" />
            )}
            {detail.reportType === 'DAY' && (detail.totalCashRecovery ?? 0) > 0 && (
              <SummaryTile label="Cash Recovery" value={<MoneyText value={detail.totalCashRecovery} />} accent="green" sublabel="Short discrepancy repaid" />
            )}
            <SummaryTile
              label="Cash Discrepancy"
              value={<MoneyText value={Math.abs(detail.cashDiscrepancy)} />}
              sublabel={detail.cashDiscrepancy > 0 ? 'Over' : detail.cashDiscrepancy < 0 ? 'Short' : 'Balanced'}
              accent={detail.cashDiscrepancy === 0 ? 'green' : detail.cashDiscrepancy < 0 ? 'red' : 'amber'}
            />
            {detail.cashDiscrepancy !== 0 && (
              <p className="col-span-full -mt-1 text-xs print:hidden">
                <Link to={discrepanciesUrl(sheetRange)} className="ui-link">
                  View the short and over shifts for {fmtDate(detail.reportDate)}
                </Link>
              </p>
            )}
            <SummaryTile label="Total Litres Sold" value={<LitresText value={detail.totalLitresSold} decimals={1} />} />
            {detail.reportType === 'DAY' && (
              <SummaryTile label="Litres Delivered" value={<LitresText value={detail.totalLitresDelivered} decimals={1} />} />
            )}
            {isOwnerOrAdmin && <SummaryTile label="Cost of Goods" value={<MoneyText value={detail.totalCostOfGoods} />} />}
            {isOwnerOrAdmin && (
              <SummaryTile
                label={detail.reportType === 'DAY' ? 'Fuel Gross Profit' : 'Gross Profit'}
                value={<MoneyText value={detail.totalGrossProfit} />}
                accent={detail.totalGrossProfit >= 0 ? 'green' : 'red'}
              />
            )}
            {isOwnerOrAdmin && detail.reportType === 'DAY' && detail.productSales && (
              <SummaryTile
                label="Product Sales Profit"
                value={<MoneyText value={detail.productSales.grossProfit} />}
                accent={detail.productSales.grossProfit >= 0 ? 'green' : 'red'}
              />
            )}
            {detail.reportType === 'DAY' && detail.expenses && (
              <SummaryTile
                label="Expenses"
                value={<MoneyText value={-detail.expenses.totalAmount} showSign />}
                accent="red"
              />
            )}
            {isOwnerOrAdmin && detail.dipPlEntries?.length > 0 && (
              <SummaryTile
                label="Dip P/L"
                value={<MoneyText value={detail.totalDipNetAmount} showSign />}
                accent={detail.totalDipNetAmount > 0 ? 'green' : 'red'}
              />
            )}
            {isOwnerOrAdmin && (detail.reportType === 'DAY' ? (
              <SummaryTile
                label="Net Profit"
                value={<MoneyText value={detail.totalNetProfit} />}
                accent={detail.totalNetProfit >= 0 ? 'green' : 'red'}
              />
            ) : detail.dipPlEntries?.length > 0 && (
              <SummaryTile
                label="Net Profit"
                value={<MoneyText value={detail.totalNetProfit} />}
                accent={detail.totalNetProfit >= 0 ? 'green' : 'red'}
              />
            ))}
          {!isOwnerOrAdmin && (
            <p className="text-xs text-ink-3 col-span-full mt-1">
              Financial details (cost, profit, P/L) are visible to Owner and Admin only.
            </p>
          )}
          </div>
        </section>

        {/* Fuel lines */}
        <section>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider">Fuel Breakdown</h3>
            <CsvExportButton
              filename={csvFilename(`balance-sheet-${reportId}-fuel`, sheetRange)}
              columns={fuelColumns}
              rows={detail.fuelLines}
            />
          </div>
          <DataTable
            caption="Fuel breakdown"
            captionHidden
            regionLabel="Fuel summary"
            columns={fuelTableColumns(isOwnerOrAdmin)}
            rows={detail.fuelLines}
            rowKey={(line) => line.fuelType}
            renderRow={(line) => <FuelLineRow line={line} shiftsHref={shiftsForFuelUrl(sheetRange, line.fuelType)} />}
            className="print:overflow-visible"
            tableClassName="min-w-[900px] print:min-w-0 print:text-[8pt]"
          />
        </section>

        {/* Shift lines */}
        <section>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider">
              Shift Breakdown <span className="text-ink-3 normal-case font-normal">({detail.shiftLines.length} shift{detail.shiftLines.length !== 1 ? 's' : ''})</span>
            </h3>
            <CsvExportButton
              filename={csvFilename(`balance-sheet-${reportId}-shifts`, sheetRange)}
              columns={SHIFT_COLUMNS}
              rows={detail.shiftLines}
            />
          </div>
          <DataTable
            caption="Shift breakdown"
            captionHidden
            regionLabel="Shift summary"
            columns={SHIFT_TABLE_COLUMNS}
            rows={detail.shiftLines}
            rowKey={(line) => line.shiftId}
            renderRow={(line) => <ShiftLineRow line={line} />}
            scroll
            className="print:overflow-visible"
            tableClassName="min-w-[960px] text-xs print:min-w-0 print:text-[7pt]"
            footer={detail.shiftLines.length > 1 && (
              <tr className="bg-surface-sunken text-ink">
                <Td colSpan={3}>Total</Td>
                <Td right><LitresText value={detail.shiftLines.reduce((s, l) => s + l.litresSold, 0)} decimals={1} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + l.expectedRevenue, 0)} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + l.cashCollected + l.upiCollected + l.cardCollected + (l.fleetCardCollected ?? 0), 0)} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + l.cashCollected, 0)} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + l.upiCollected, 0)} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + l.cardCollected, 0)} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + (l.fleetCardCollected ?? 0), 0)} /></Td>
                <Td right><MoneyText value={detail.shiftLines.reduce((s, l) => s + l.creditAmount, 0)} /></Td>
                <DiscrepancyTd value={detail.shiftLines.reduce((s, l) => s + l.discrepancy, 0)} />
              </tr>
            )}
          />
        </section>

        {/* Meter Reading Amendments — only shown when amendments occurred in this period */}
        {detail.meterAmendments?.length > 0 && (
          <MeterAmendmentsSection detail={detail} />
        )}

        {/* Dip P/L — shown when any dip activity (maintenance removal or dipstick check) occurred */}
        {isOwnerOrAdmin && detail.dipPlEntries?.length > 0 && (
          <DipPlSection detail={detail} />
        )}

        {/* Ancillary Product Sales — DAY reports only */}
        {detail.reportType === 'DAY' && detail.productSales && (
          <ProductSalesSection productSales={detail.productSales} />
        )}

        {/* Approved Expenses — DAY reports only */}
        {detail.reportType === 'DAY' && detail.expenses && (
          <ExpensesSection expenses={detail.expenses} />
        )}

        {/* Payment Settlements — DAY reports only */}
        {detail.reportType === 'DAY' && detail.settlementSummary && (
          <SettlementSection summary={detail.settlementSummary} />
        )}
      </div>
    </div>
  )
}
