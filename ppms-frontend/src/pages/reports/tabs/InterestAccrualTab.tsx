import { useState } from 'react'
import { serverMessage } from '../../../utils/apiError'
import { useQuery } from '@tanstack/react-query'
import { reportApi } from '../../../api/reportApi'
import type { InterestAccrualReport, InterestChargeLine } from '../../../api/reportApi'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { csvFilename } from '../../../utils/csv'
import type { CsvColumn } from '../../../utils/csv'
import type { DateRange } from '../../../utils/datePresets'
import { useReportRange, yesterdayToTodayRange } from '../useReportRange'
import { DateRangeInputs, ErrorBox, SummaryCard } from '../shared'
import { fmtAmt, fmtShortDate } from '../reportFormat'
import { MoneyText } from '../../../components/AmountText'

type InterestCsvRow = InterestChargeLine & { clientName: string }

const CSV_COLUMNS: CsvColumn<InterestCsvRow>[] = [
  { header: 'Client', value: (row) => row.clientName },
  { header: 'Period from', value: (row) => row.periodFrom, kind: 'date' },
  { header: 'Period to', value: (row) => row.periodTo, kind: 'date' },
  { header: 'Days', value: (row) => row.daysApplied, kind: 'number' },
  { header: 'Outstanding balance', value: (row) => row.outstandingBalance, kind: 'money' },
  { header: 'Monthly rate %', value: (row) => row.rateApplied, kind: 'number' },
  { header: 'Interest', value: (row) => row.amount, kind: 'money' },
  { header: 'Source', value: (row) => row.source },
]

// ── Interest Accrual Tab ───────────────────────────────────────────────────────

export function InterestAccrualTab({ pumpId }: { pumpId: number }) {
  const [query, setQuery] = useReportRange(yesterdayToTodayRange)
  const [from, setFrom] = useState(query.from)
  const [to,   setTo]   = useState(query.to)

  const { data, isFetching, error } = useQuery<InterestAccrualReport>({
    queryKey: ['report-interest', pumpId, query.from, query.to],
    queryFn:  () => reportApi.getInterestAccrual(pumpId, query.from, query.to),
  })

  const applyRange = (range: DateRange) => {
    setFrom(range.from)
    setTo(range.to)
    setQuery(range)
  }

  const csvRows: InterestCsvRow[] = data
    ? data.clients.flatMap((client) => client.charges.map((charge) => ({ ...charge, clientName: client.clientName })))
    : []

  return (
    <div className="space-y-4">
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
            disabled={!data || data.clients.length === 0}
            className="ui-btn ui-btn-secondary"
          >
            Print PDF
          </button>
          <CsvExportButton filename={csvFilename('interest-accrual', query)} columns={CSV_COLUMNS} rows={csvRows} />
        </div>
      </div>

      {error && <ErrorBox message={serverMessage(error) ?? 'Failed to load report'} />}

      {data && (
        <div className="space-y-4">
          {/* Summary */}
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
            <SummaryCard label="Total Charges"  value={String(data.totalCharges)}  color="text-ink" />
            <SummaryCard label="Total Interest" value={<MoneyText value={data.totalInterest} />} color="text-bad" />
            <SummaryCard label="Clients"        value={String(data.clients.length)} color="text-ink" />
          </div>

          {data.clients.length === 0 ? (
            <p className="ui-empty py-8">No interest charges found for this period.</p>
          ) : (
            data.clients.map(client => (
              <div key={client.clientId} className="ui-card p-0 overflow-hidden">
                {/* Client header */}
                <div className="flex items-center justify-between px-4 py-3 border-b border-border bg-surface-sunken">
                  <div>
                    <p className="text-sm font-semibold text-ink">{client.clientName}</p>
                    <p className="text-xs text-ink-3">{client.chargeCount} charge{client.chargeCount !== 1 ? 's' : ''}</p>
                  </div>
                  <MoneyText value={client.totalInterest} tone="bad" className="text-sm font-bold" />
                </div>

                {/* Charge lines */}
                <div className="divide-y divide-border text-xs">
                  {client.charges.map(c => (
                    <div key={c.id} className="flex items-center justify-between gap-3 px-4 py-2.5">
                      <div className="text-ink-3 space-y-0.5">
                        <p>{fmtShortDate(c.periodFrom)} – {fmtShortDate(c.periodTo)} ({c.daysApplied} days)</p>
                        <p>Balance {fmtAmt(c.outstandingBalance)} @ {c.rateApplied}%/month · {c.source}</p>
                      </div>
                      <MoneyText value={c.amount} className="font-medium text-ink flex-shrink-0" />
                    </div>
                  ))}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  )
}
