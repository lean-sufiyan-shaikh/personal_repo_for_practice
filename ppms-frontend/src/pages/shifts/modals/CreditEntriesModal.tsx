import type { Shift, CreditEntry } from '../../../types/shift'
import { Modal } from '../../../components/Modal'
import { StatusBadge } from '../../../components/StatusBadge'
import { MoneyText } from '../../../components/AmountText'

// ── Credit entries modal ───────────────────────────────────────────────────────

export function CreditEntriesModal({
  shift,
  entries,
  creditTotal,
  onClose,
}: {
  shift: Shift
  entries: CreditEntry[]
  creditTotal: number
  onClose: () => void
}) {
  return (
    <tr>
      <td colSpan={8} className="p-0">
        <Modal
          title="Credit Sales"
          subtitle={`Shift #${shift.id} · Nozzle ${shift.nozzles.map((n) => `#${n.nozzleNumber}`).join(', ')} · ${shift.operatorName} · ${shift.shiftDate}`}
          headerExtra={
            <p className="mt-1 text-xs text-ink-3">
              {entries.length} {entries.length === 1 ? 'client' : 'clients'} with outstanding credit
            </p>
          }
          tone="warning"
          size="md"
          onClose={onClose}
          closeOnBackdropClick
          bodyClassName="p-0 divide-y divide-border max-h-96"
          footerClassName="justify-between bg-warn-bg"
          footer={
            <>
              <span className="text-sm font-semibold text-warn">Total Credit</span>
              <MoneyText value={creditTotal} className="text-base font-bold text-warn" />
            </>
          }
        >
          {entries.map((entry, idx) => (
            <div key={entry.id} className="flex items-start gap-3 px-5 py-3.5">
              <div className="w-7 h-7 rounded-full bg-warn-bg text-warn text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">
                {idx + 1}
              </div>

              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className="text-sm font-semibold text-ink">{entry.clientName}</p>
                  {entry.fuelType && <StatusBadge tone="neutral" label={entry.fuelType} />}
                </div>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 mt-1">
                  {entry.billNo ? (
                    <span className="text-xs text-ink-3">
                      Bill: <span className="font-mono bg-surface-sunken px-1 py-0.5 rounded-control text-ink-2">{entry.billNo}</span>
                    </span>
                  ) : (
                    <span className="text-xs text-ink-3">No bill</span>
                  )}
                  {entry.description && (
                    <span className="text-xs text-ink-3 italic">"{entry.description}"</span>
                  )}
                </div>
              </div>

              <MoneyText value={entry.amount} className="text-sm font-bold text-warn shrink-0" />
            </div>
          ))}
        </Modal>
      </td>
    </tr>
  )
}
