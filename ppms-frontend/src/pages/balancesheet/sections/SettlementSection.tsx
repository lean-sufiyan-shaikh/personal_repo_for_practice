import { Check } from 'lucide-react'
import type { SettlementSummary } from '../../../api/balanceSheetApi'
import { Td } from '../bsTableCells'
import { bsColumn } from '../bsFormat'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { MoneyText } from '../../../components/AmountText'

const TYPE_LABELS: Record<string, string> = { UPI: 'UPI', CARD: 'Card', FLEET_CARD: 'Fleet Card' }

interface SettlementRow {
  type: string
  settled: number
  pending: number
}

const COLUMNS: DataTableColumn<SettlementRow>[] = [
  bsColumn('type', 'Payment Type'),
  bsColumn('settled', 'Settled on this Date', true),
  bsColumn('pending', 'Cumulative Wallet Pending', true),
]

// ── Payment settlement section (DAY reports only) ──────────────────────────────

export function SettlementSection({ summary }: { summary: SettlementSummary }) {
  const rows: SettlementRow[] = [
    { type: 'UPI',        settled: summary.upiSettledOnDate,        pending: summary.walletUpiPending },
    { type: 'CARD',       settled: summary.cardSettledOnDate,        pending: summary.walletCardPending },
    { type: 'FLEET_CARD', settled: summary.fleetCardSettledOnDate,   pending: summary.walletFleetCardPending },
  ]

  const totalSettled = rows.reduce((s, r) => s + r.settled, 0)
  const totalPending = rows.reduce((s, r) => s + r.pending, 0)

  return (
    <section className="mt-6 space-y-3">
      <h3 className="text-sm font-semibold text-ink-2 uppercase tracking-wide">
        Digital Payment Settlements
      </h3>
      <DataTable
        caption="Digital payment settlements"
        captionHidden
        columns={COLUMNS}
        rows={rows}
        rowKey={(r) => r.type}
        renderRow={(r) => (
          <tr>
            <Td>{TYPE_LABELS[r.type]}</Td>
            <Td right>
              {r.settled > 0
                ? <MoneyText value={r.settled} tone="ok" className="font-medium" />
                : <span className="text-ink-3">—</span>
              }
            </Td>
            <Td right>
              {r.pending !== 0
                ? <MoneyText value={r.pending} tone={r.pending > 0 ? 'warn' : 'ok'} className={r.pending > 0 ? 'font-medium' : ''} />
                : <span className="inline-flex items-center gap-1 text-ink-3">Settled<Check size={11} strokeWidth={2.5} /></span>
              }
            </Td>
          </tr>
        )}
        footer={
          <tr className="bg-surface-sunken">
            <Td>Total</Td>
            <Td right>{totalSettled > 0 ? <MoneyText value={totalSettled} /> : '—'}</Td>
            <Td right><MoneyText value={totalPending} tone={totalPending > 0 ? 'warn' : 'ok'} /></Td>
          </tr>
        }
      />
      {summary.settlementsOnDate.length > 0 && (
        <div className="ui-card-plain ui-card-muted p-3 text-sm">
          <p className="text-xs font-medium text-ink-3 mb-2 uppercase tracking-wide">Recorded on this date</p>
          <div className="space-y-1">
            {summary.settlementsOnDate.map(s => (
              <div key={s.id} className="flex items-center gap-3 text-xs text-ink-2">
                <span className="font-medium">{TYPE_LABELS[s.paymentType]}</span>
                <MoneyText value={s.amountReceived} tone="ok" className="font-semibold" />
                {s.notes && <span className="text-ink-3">· {s.notes}</span>}
                <span className="text-ink-3 ml-auto">by {s.recordedByUserName}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
