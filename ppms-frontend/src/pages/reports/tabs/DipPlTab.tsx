import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { reportApi } from '../../../api/reportApi'
import type { DipPlEntry } from '../../../api/reportApi'
import { Pagination } from '../../../components/Pagination'
import { pageSlice } from '../../../utils/pageSlice'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DateRangeInputs, ErrorBox, PrintHeader, SummaryCard } from '../shared'
import { fmtDateTime, fmtQty } from '../reportFormat'
import { FUEL_BADGE } from '../../../constants/fuel'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { MoneyText } from '../../../components/AmountText'
import { codeLabel } from '../../../constants/labels'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import type { DateRange } from '../../../utils/datePresets'

const CSV_COLUMNS: CsvColumn<DipPlEntry>[] = [
  { header: 'Recorded on', value: (row) => row.recordedAt, kind: 'date' },
  { header: 'Type', value: (row) => (row.type === 'DIP_CHECK' ? 'Dip check' : 'Maintenance') },
  { header: 'Fuel', value: (row) => codeLabel(row.fuelType) },
  { header: 'Litres', value: (row) => row.litres.toFixed(2), kind: 'number' },
  { header: 'Profit or loss', value: (row) => row.monetaryAmount, kind: 'money' },
  { header: 'Notes', value: (row) => row.notes },
]

function signTone(value: number): StatusTone {
  return value > 0 ? 'ok' : value < 0 ? 'bad' : 'neutral'
}

function dipEntryKey(e: DipPlEntry) {
  return `${e.type}-${e.fuelType}-${e.recordedAt}`
}

const COLUMNS: DataTableColumn<DipPlEntry>[] = [
  { key: 'recordedAt', header: 'Date & Time', cell: (e) => <span className="text-ink-2 text-xs">{fmtDateTime(e.recordedAt)}</span> },
  {
    key: 'type', header: 'Type',
    cell: (e) => e.type === 'DIP_CHECK' ? <StatusBadge tone="info" label="Dip Check" /> : <StatusBadge tone="warn" label="Maintenance" />,
  },
  {
    key: 'fuel', header: 'Fuel',
    cell: (e) => (
      <span className={`inline-block px-2 py-0.5 rounded-badge text-xs font-medium ${FUEL_BADGE[e.fuelType] ?? 'bg-surface-sunken text-ink-2'}`}>
        {codeLabel(e.fuelType)}
      </span>
    ),
  },
  {
    key: 'litres', header: 'Litres', numeric: true,
    cell: (e) => {
      const color = e.type === 'DIP_CHECK'
        ? (e.litres > 0 ? 'text-ok' : e.litres < 0 ? 'text-bad' : 'text-ink-3')
        : 'text-ink'
      return (
        <span className={`font-mono ${color}`}>
          {e.type === 'DIP_CHECK' && e.litres > 0 ? '+' : ''}{fmtQty(e.litres)}
        </span>
      )
    },
  },
  { key: 'pl', header: 'P/L', numeric: true, cell: (e) => <MoneyText value={e.monetaryAmount} showSign tone={signTone(e.monetaryAmount)} className="font-medium" /> },
  {
    key: 'notes', header: 'Notes',
    cell: (e) => <span className="block max-w-52 truncate text-ink-2 text-xs" title={e.notes ?? ''}>{e.notes ?? '—'}</span>,
  },
]

// ── Dip P/L Tab ───────────────────────────────────────────────────────────────

export function DipPlTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)
  const [page, setPage]   = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data, isFetching, error } = useQuery<DipPlEntry[]>({
    queryKey: ['report-dip-pl', pumpId, query.from, query.to],
    queryFn: () => reportApi.getDipPl(pumpId, query.from, query.to),
  })

  const entries = data ?? []
  const paged   = data ? pageSlice(entries, page, pageSize) : null

  // Net P/L: sum of all monetaryAmounts (negative = net loss, positive = net gain)
  const netPl    = entries.reduce((s, e) => s + e.monetaryAmount, 0)
  const isNetGain = netPl > 0
  const isNetLoss = netPl < 0

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
          className="ui-btn ui-btn-primary disabled:opacity-60"
        >
          {isFetching ? 'Loading...' : 'Generate'}
        </button>

        <button
          onClick={() => window.print()}
          disabled={!data || entries.length === 0}
          className="ui-btn ui-btn-secondary"
        >
          Print PDF
        </button>
        <CsvExportButton filename={csvFilename('dip-pl', query)} columns={CSV_COLUMNS} rows={entries} />
        </div>
      </div>

      {error && <ErrorBox message={error.message ?? 'Failed to load dip P/L entries'} />}

      {data && paged && (
        <div className="space-y-4 print:hidden">
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <SummaryCard label="Total Entries"  value={String(entries.length)}  color="text-ink" />
            <SummaryCard
              label="Net P/L"
              value={<MoneyText value={netPl} showSign />}
              color={isNetGain ? 'text-ok' : isNetLoss ? 'text-bad' : 'text-ink-3'}
            />
            <SummaryCard
              label="Dip Checks"
              value={String(entries.filter(e => e.type === 'DIP_CHECK').length)}
              color="text-primary"
            />
          </div>

          {entries.length === 0 ? (
            <div className="ui-empty py-6">No dip P/L entries found for this date range.</div>
          ) : (
            <>
              <DataTable
                caption="Dip profit and loss entries"
                captionHidden
                columns={COLUMNS}
                rows={paged.content}
                rowKey={dipEntryKey}
                scroll
                footer={entries.length > 1 && (
                  <tr className="bg-surface-sunken text-ink">
                    <td colSpan={4}>Net P/L</td>
                    <td className="text-right"><MoneyText value={netPl} showSign tone={signTone(netPl)} /></td>
                    <td />
                  </tr>
                )}
              />
              <Pagination data={paged} onPageChange={setPage} onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
            </>
          )}
        </div>
      )}

      {/* Print: full dataset */}
      {data && entries.length > 0 && (
        <div className="hidden print:block space-y-4">
          <PrintHeader title="Dip P/L Report" from={query.from} to={query.to} />
          <div className="grid grid-cols-3 gap-3">
            <SummaryCard label="Total Entries"  value={String(entries.length)}  color="text-ink" />
            <SummaryCard
              label="Net P/L"
              value={<MoneyText value={netPl} showSign />}
              color={isNetGain ? 'text-ok' : isNetLoss ? 'text-bad' : 'text-ink-3'}
            />
            <SummaryCard
              label="Dip Checks"
              value={String(entries.filter(e => e.type === 'DIP_CHECK').length)}
              color="text-primary"
            />
          </div>
          <DataTable
            caption="Dip profit and loss summary"
            captionHidden
            columns={COLUMNS}
            rows={entries}
            rowKey={dipEntryKey}
            footer={entries.length > 1 && (
              <tr className="bg-surface-sunken text-ink">
                <td colSpan={4}>Net P/L</td>
                <td className="text-right"><MoneyText value={netPl} showSign tone={signTone(netPl)} /></td>
                <td />
              </tr>
            )}
          />
        </div>
      )}
    </div>
  )
}
