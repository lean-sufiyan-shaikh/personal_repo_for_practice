import type { BalanceSheetDetail, MeterAmendmentLine } from '../../../api/balanceSheetApi'
import { Td } from '../bsTableCells'
import { FUEL_BADGE, IST_TIME_FORMATTER, bsColumn } from '../bsFormat'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { codeLabel } from '../../../constants/labels'

const COLUMNS: DataTableColumn<MeterAmendmentLine>[] = [
  bsColumn('fuel', 'Fuel'),
  bsColumn('type', 'Type'),
  bsColumn('previous', 'Previous', true),
  bsColumn('new', 'New', true),
  bsColumn('change', 'Change', true),
  bsColumn('reason', 'Reason'),
  bsColumn('by', 'By'),
  bsColumn('time', 'Time'),
]

export function MeterAmendmentsSection({ detail }: { detail: BalanceSheetDetail }) {
  return (
    <section>
      <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider mb-3">
        Meter Reading Amendments
        <span className="ml-2 text-ink-3 normal-case font-normal">
          ({detail.meterAmendments.length} amendment{detail.meterAmendments.length !== 1 ? 's' : ''} during this period)
        </span>
      </h3>
      <DataTable
        caption="Meter reading amendments"
        captionHidden
        regionLabel="Meter amendments"
        columns={COLUMNS}
        rows={detail.meterAmendments}
        rowKey={(a) => a.id}
        renderRow={(a) => {
          const badge = FUEL_BADGE[a.fuelType] ?? 'bg-surface-sunken text-ink-2'
          const deltaColor = a.delta < 0 ? 'text-bad' : a.delta > 0 ? 'text-warn' : 'text-ink-3'
          return (
            <tr>
              <Td>
                <span className={`inline-block px-2 py-0.5 rounded-badge text-xs font-medium ${badge}`}>
                  {codeLabel(a.fuelType)}
                </span>
              </Td>
              <Td>
                <span className="text-xs font-medium text-ink-2">
                  {a.adjustmentType === 'RESET' ? 'Reset to 0' : 'Custom'}
                </span>
              </Td>
              <Td right><span className="font-mono text-ink-2">{a.previousReading.toFixed(2)}</span></Td>
              <Td right><span className="font-mono text-ink font-medium">{a.newReading.toFixed(2)}</span></Td>
              <Td right>
                <span className={`font-medium ${deltaColor}`}>
                  {a.delta > 0 ? '+' : ''}{a.delta.toFixed(2)}
                </span>
              </Td>
              <Td><span className="text-xs text-ink-3">{a.reason}</span></Td>
              <Td><span className="text-xs text-ink-3">{a.recordedByUserName}</span></Td>
              <Td>
                <span className="text-xs text-ink-3">
                  {IST_TIME_FORMATTER.format(new Date(a.createdAt))}
                </span>
              </Td>
            </tr>
          )
        }}
      />
    </section>
  )
}
