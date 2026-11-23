import type { BalanceSheetDetail, DipPlLine } from '../../../api/balanceSheetApi'
import { Td } from '../bsTableCells'
import { FUEL_BADGE, IST_TIME_FORMATTER, bsColumn } from '../bsFormat'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { StatusBadge } from '../../../components/StatusBadge'
import { MoneyText } from '../../../components/AmountText'
import type { StatusTone } from '../../../components/StatusBadge'
import { codeLabel } from '../../../constants/labels'

const COLUMNS: DataTableColumn<DipPlLine>[] = [
  bsColumn('type', 'Type'),
  bsColumn('fuel', 'Fuel'),
  bsColumn('litres', 'Litres', true),
  bsColumn('pl', 'P/L', true),
  bsColumn('time', 'Time'),
  bsColumn('notes', 'Notes'),
]

function signTone(value: number): StatusTone {
  return value > 0 ? 'ok' : value < 0 ? 'bad' : 'neutral'
}

export function DipPlSection({ detail }: { detail: BalanceSheetDetail }) {
  return (
    <section>
      <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider mb-3">
        Dip P/L
        <span className="ml-2 text-ink-3 normal-case font-normal">
          ({detail.dipPlEntries.length} entr{detail.dipPlEntries.length !== 1 ? 'ies' : 'y'})
        </span>
      </h3>
      <DataTable
        caption="Dip P/L"
        captionHidden
        regionLabel="Dip profit and loss"
        columns={COLUMNS}
        rows={detail.dipPlEntries}
        rowKey={(entry) => `${entry.type}-${entry.fuelType}-${entry.recordedAt}`}
        renderRow={(entry) => {
          const badge = FUEL_BADGE[entry.fuelType] ?? 'bg-surface-sunken text-ink-2'
          const litresColor = entry.type === 'DIP_CHECK'
            ? (entry.litres > 0 ? 'text-ok' : entry.litres < 0 ? 'text-bad' : 'text-ink-3')
            : 'text-ink'
          return (
            <tr>
              <Td>
                {entry.type === 'DIP_CHECK'
                  ? <StatusBadge tone="info" label="Dip Check" />
                  : <StatusBadge tone="warn" label="Maintenance" />}
              </Td>
              <Td>
                <span className={`inline-block px-2 py-0.5 rounded-badge text-xs font-medium ${badge}`}>
                  {codeLabel(entry.fuelType)}
                </span>
              </Td>
              <Td right>
                <span className={`font-mono ${litresColor}`}>
                  {entry.type === 'DIP_CHECK' && entry.litres > 0 ? '+' : ''}{entry.litres.toFixed(2)} L
                </span>
              </Td>
              <Td right>
                <MoneyText value={entry.monetaryAmount} showSign tone={signTone(entry.monetaryAmount)} className="font-medium" />
              </Td>
              <Td>
                <span className="text-xs text-ink-3">
                  {IST_TIME_FORMATTER.format(new Date(entry.recordedAt))}
                </span>
              </Td>
              <Td>
                <span className="text-xs text-ink-3 max-w-40 truncate block" title={entry.notes ?? ''}>
                  {entry.notes ?? '—'}
                </span>
              </Td>
            </tr>
          )
        }}
        footer={
          <tr className="bg-surface-sunken">
            <Td colSpan={3}>Net Dip P/L</Td>
            <Td right>
              <MoneyText value={detail.totalDipNetAmount} showSign tone={signTone(detail.totalDipNetAmount)} />
            </Td>
            <Td colSpan={2} />
          </tr>
        }
      />
    </section>
  )
}
