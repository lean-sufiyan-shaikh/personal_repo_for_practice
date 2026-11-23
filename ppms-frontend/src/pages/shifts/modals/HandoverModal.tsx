import { useEffect } from 'react'
import { Printer } from 'lucide-react'
import type { FuelReading, Shift } from '../../../types/shift'
import { formatIstDateTime } from '../../../utils/date'
import { Modal } from '../../../components/Modal'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { formatCurrencyOrDash } from '../../../utils/format'

// ── Handover Report Modal ─────────────────────────────────────────────────────

export function HandoverModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const readingColumns: DataTableColumn<FuelReading>[] = [
    {
      key: 'nozzle',
      header: 'Nozzle',
      cell: (r) => {
        const nozzle = shift.nozzles.find(n => n.id === r.nozzleId)
        return <span className="font-medium">{nozzle ? `#${nozzle.nozzleNumber} ${r.fuelType}` : r.fuelType}</span>
      },
    },
    { key: 'opening', header: 'Opening', numeric: true, cell: (r) => r.startReading ?? '—' },
    { key: 'closing', header: 'Closing', numeric: true, cell: (r) => r.endReading ?? '—' },
    { key: 'sold', header: 'Litres Sold', numeric: true, cell: (r) => <LitresText value={r.unitsSold} className="font-semibold" /> },
    { key: 'rate', header: 'Rate', numeric: true, cell: (r) => <MoneyText value={r.priceSnapshot} className="text-ink-3" /> },
  ]

  // Tag <body> while this modal is open so @media print can isolate it:
  // hide #root and free the panel from its max-height/overflow constraints.
  useEffect(() => {
    document.body.classList.add('handover-printing')
    return () => document.body.classList.remove('handover-printing')
  }, [])

  return (
    <Modal
      title="Shift Handover Report"
      subtitle={
        <>
          Shift #{shift.id} · {shift.operatorName} · {shift.shiftDate}
          {' · '}
          {formatIstDateTime(shift.actualStartTime, { hour: '2-digit', minute: '2-digit' })}
          {' → '}
          {shift.actualEndTime
            ? formatIstDateTime(shift.actualEndTime, { hour: '2-digit', minute: '2-digit' })
            : 'Open'}
        </>
      }
      headerActions={
        <button type="button" onClick={() => window.print()} className="ui-btn ui-btn-secondary ui-btn-compact inline-flex items-center gap-1.5 text-xs">
          <Printer size={14} strokeWidth={2} aria-hidden="true" /> Print
        </button>
      }
      tone="info"
      size="md-wide"
      onClose={onClose}
      closeOnBackdropClick
      headerClassName="print:hidden"
      bodyClassName="p-6 space-y-5 text-sm print:overflow-visible"
    >
          {/* Title for print */}
          <div className="hidden print:block text-center mb-4">
            <h2 className="text-xl font-bold">Shift Handover Report</h2>
            <p className="text-ink-3 text-xs mt-1">Shift #{shift.id}</p>
          </div>

          <div className="grid grid-cols-2 gap-3">
            {[
              { label: 'Operator',     value: shift.operatorName },
              { label: 'Date',         value: shift.shiftDate },
              { label: 'DU',           value: shift.duName ?? `DU #${shift.duNumber}` },
              { label: 'Shift Window', value: shift.shiftWindow ?? '—' },
            ].map(({ label, value }) => (
              <div key={label}>
                <p className="text-xs text-ink-3 uppercase tracking-wide mb-0.5">{label}</p>
                <p className="font-semibold text-ink">{value}</p>
              </div>
            ))}
          </div>

          {shift.fuelReadings && shift.fuelReadings.length > 0 && (
            <DataTable
              caption="Meter Readings"
              columns={readingColumns}
              rows={shift.fuelReadings}
              rowKey={(r) => r.nozzleId}
              tableClassName="text-xs"
            />
          )}

          <div>
            <p className="text-xs font-semibold text-ink-3 uppercase tracking-wide mb-2">Collections</p>
            <div className="grid grid-cols-2 gap-2">
              {[
                { label: 'Cash',       val: shift.cashCollected },
                { label: 'UPI',        val: shift.upiCollected },
                { label: 'Card',       val: shift.cardCollected },
                { label: 'Fleet Card', val: shift.fleetCardCollected },
                { label: 'Credit',     val: shift.creditTotal },
              ].map(({ label, val }) => (
                <div key={label} className="flex justify-between bg-surface-sunken rounded-control px-3 py-2">
                  <span className="text-ink-3">{label}</span>
                  <MoneyText value={val} className="font-semibold text-ink" />
                </div>
              ))}
              <div className="flex justify-between bg-surface-sunken rounded-control px-3 py-2 col-span-2">
                <span className="font-semibold text-ink">Total Due</span>
                <MoneyText value={shift.totalAmountDue} className="font-bold text-ink" />
              </div>
            </div>
          </div>

          {shift.discrepancyAmount != null && (
            <div className={`rounded-card border p-3 ${shift.discrepancyType === 'SHORT' ? 'bg-bad-bg border-bad' : 'bg-warn-bg border-warn'}`}>
              <p className="text-xs font-semibold uppercase tracking-wide mb-1 text-ink-2">Discrepancy</p>
              <p className={`font-bold text-sm ${shift.discrepancyType === 'SHORT' ? 'text-bad' : 'text-warn'}`}>
                {shift.discrepancyType}: {formatCurrencyOrDash(shift.discrepancyAmount)}
              </p>
              {shift.discrepancyReason && (
                <p className="text-xs text-ink-3 mt-1">Reason: {shift.discrepancyReason}</p>
              )}
            </div>
          )}

          <div className="hidden print:grid grid-cols-2 gap-10 pt-8">
            <div>
              <div className="border-b border-control mb-1" />
              <p className="text-xs text-ink-3">Operator Signature</p>
            </div>
            <div>
              <div className="border-b border-control mb-1" />
              <p className="text-xs text-ink-3">Supervisor Signature</p>
            </div>
          </div>
    </Modal>
  )
}
