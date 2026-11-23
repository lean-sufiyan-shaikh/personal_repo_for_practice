import { LoadingButton } from '../../../components/LoadingButton'
import { DiscrepancyBadge } from '../components/DiscrepancyBadge'
import { fmt } from '../components/creditRow'
import { discrepancyTypeOf, needsOwnerApproval } from './closeShiftTotals'

export interface NozzleSaleLine {
  nozzleId: number
  label: string
  units: number
  unit: string
  amount: number
}

export interface PaymentSplit {
  cash: number
  upi: number
  card: number
  fleetCard: number
  credit: number
}

interface Props {
  isHandover: boolean
  incomingOperatorName: string | null
  lines: NozzleSaleLine[]
  totalDue: number
  payments: PaymentSplit
  creditEntryCount: number
  totalCollected: number
  diff: number
  discrepancyReason: string
  escalationThreshold: number | null
  serverError: string | null
  isSubmitting: boolean
  onConfirm: () => void
  onEdit: () => void
}

export function CloseShiftReview({
  isHandover, incomingOperatorName, lines, totalDue, payments, creditEntryCount, totalCollected, diff,
  discrepancyReason, escalationThreshold, serverError, isSubmitting, onConfirm, onEdit,
}: Props) {
  const discrepancyType = discrepancyTypeOf(diff)
  const paymentRows: Array<[string, number]> = [
    ['Cash', payments.cash],
    ['UPI', payments.upi],
    ['Card', payments.card],
    ['Fleet card', payments.fleetCard],
    [`Credit (${creditEntryCount} ${creditEntryCount === 1 ? 'entry' : 'entries'})`, payments.credit],
  ]

  return (
    <>
      <div className="ui-modal-body space-y-5 text-sm text-ink">
        <p className="text-ink-2">
          {isHandover ? 'Handing over' : 'Closing'} can&apos;t be undone. Check these figures with the operator.
        </p>

        {isHandover && (
          <div className="flex items-center justify-between rounded-card border border-border px-3 py-2">
            <span className="text-ink-2">Incoming operator</span>
            <span className="font-semibold">{incomingOperatorName ?? '—'}</span>
          </div>
        )}

        <section aria-labelledby="review-sales-heading">
          <h3 id="review-sales-heading" className="mb-2 font-semibold">Sales by nozzle</h3>
          <table className="w-full">
            <thead>
              <tr className="border-b border-border text-xs text-ink-3">
                <th scope="col" className="py-1.5 text-left font-medium">Nozzle</th>
                <th scope="col" className="py-1.5 text-right font-medium">Sold</th>
                <th scope="col" className="py-1.5 text-right font-medium">Amount</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <tr key={line.nozzleId} className="border-b border-border">
                  <td className="py-2">{line.label}</td>
                  <td className="py-2 text-right tabular-nums">{line.units.toFixed(3)} {line.unit}</td>
                  <td className="py-2 text-right tabular-nums">₹{fmt(line.amount)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colSpan={2} className="pt-2 text-left font-semibold">Total due</th>
                <td className="pt-2 text-right text-base font-bold tabular-nums">₹{fmt(totalDue)}</td>
              </tr>
            </tfoot>
          </table>
        </section>

        <section aria-labelledby="review-collected-heading">
          <h3 id="review-collected-heading" className="mb-2 font-semibold">Collected</h3>
          <dl className="divide-y divide-border rounded-card border border-border">
            {paymentRows.map(([label, amount]) => (
              <div key={label} className="flex justify-between px-3 py-2">
                <dt className="text-ink-2">{label}</dt>
                <dd className="tabular-nums">₹{fmt(amount)}</dd>
              </div>
            ))}
            <div className="flex justify-between bg-surface-sunken px-3 py-2 font-semibold">
              <dt>Total collected</dt>
              <dd className="tabular-nums">₹{fmt(totalCollected)}</dd>
            </div>
          </dl>
        </section>

        <section aria-labelledby="review-result-heading" className="space-y-2">
          <div className="flex items-center justify-between gap-3">
            <h3 id="review-result-heading" className="font-semibold">Result</h3>
            <DiscrepancyBadge type={discrepancyType} amount={Math.abs(diff)} />
          </div>
          {discrepancyType && discrepancyReason.trim() && (
            <p className="text-ink-2">Reason: {discrepancyReason.trim()}</p>
          )}
          {discrepancyType && needsOwnerApproval(diff, escalationThreshold) && (
            <p className="ui-alert ui-alert-warning">
              This is more than the ₹{fmt(escalationThreshold ?? 0)} limit, so the owner or admin must approve it.
            </p>
          )}
        </section>

        {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
      </div>

      <div className="ui-modal-footer ui-modal-footer--large">
        <button type="button" onClick={onEdit} disabled={isSubmitting} className="ui-btn ui-btn-secondary flex-1">
          Edit
        </button>
        <LoadingButton type="button" onClick={onConfirm} isLoading={isSubmitting}
          loadingLabel={isHandover ? 'Handing over the shift' : 'Closing the shift'}
          className="ui-btn-primary flex-1">
          {isHandover ? 'Confirm handover' : 'Confirm close'}
        </LoadingButton>
      </div>
    </>
  )
}
