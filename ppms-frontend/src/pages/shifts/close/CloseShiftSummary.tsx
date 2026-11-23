import { Printer } from 'lucide-react'
import { LoadingButton } from '../../../components/LoadingButton'
import { DiscrepancyBadge } from '../components/DiscrepancyBadge'
import { fmt } from '../components/creditRow'
import { discrepancyTypeOf } from './closeShiftTotals'

interface Props {
  incomingOperatorName: string | null
  totalDue: number
  totalCollected: number
  diff: number
  creditEntryCount: number
  isEscalated: boolean
  isPreparingPrint: boolean
  printError: string | null
  onPrint: () => void
  /** Absent when the next shift cannot be opened from here (handover already opened it, or no permission). */
  onOpenNextShift?: () => void
  onDone: () => void
}

/** The one confirmation after a shift is closed or handed over, with what to do next. */
export function CloseShiftSummary({
  incomingOperatorName, totalDue, totalCollected, diff, creditEntryCount, isEscalated,
  isPreparingPrint, printError, onPrint, onOpenNextShift, onDone,
}: Props) {
  const discrepancyType = discrepancyTypeOf(diff)
  const figures: Array<[string, string]> = [
    ['Total due', `₹${fmt(totalDue)}`],
    ['Collected', `₹${fmt(totalCollected)}`],
    ['Credit entries', String(creditEntryCount)],
  ]

  return (
    <>
      <div className="ui-modal-body space-y-4 text-sm text-ink">
        <div className="flex items-center justify-between gap-3">
          <span className="font-semibold">Result</span>
          <DiscrepancyBadge type={discrepancyType} amount={Math.abs(diff)} />
        </div>
        <dl className="divide-y divide-border rounded-card border border-border">
          {figures.map(([label, value]) => (
            <div key={label} className="flex justify-between px-3 py-2">
              <dt className="text-ink-2">{label}</dt>
              <dd className="font-semibold tabular-nums">{value}</dd>
            </div>
          ))}
        </dl>
        {incomingOperatorName && (
          <p className="ui-alert ui-alert-success">A new shift is open for {incomingOperatorName} on the same nozzles.</p>
        )}
        {discrepancyType && (
          <p className="text-ink-2">
            {isEscalated
              ? 'The difference is above the approval limit and waits for the owner or admin.'
              : 'The difference is listed under Discrepancies for a manager to resolve.'}
          </p>
        )}
        {printError && <p role="alert" className="ui-alert ui-alert-danger">{printError}</p>}
      </div>

      <div className="ui-modal-footer ui-modal-footer--large grid grid-cols-2">
        {onOpenNextShift && (
          <button type="button" onClick={onOpenNextShift} className="ui-btn ui-btn-primary col-span-2">
            Open next shift
          </button>
        )}
        <LoadingButton type="button" onClick={onPrint} isLoading={isPreparingPrint} loadingLabel="Preparing the report"
          className="ui-btn-secondary">
          <Printer size={16} strokeWidth={2} aria-hidden="true" />Print / share
        </LoadingButton>
        <button type="button" onClick={onDone} className={`ui-btn ${onOpenNextShift ? 'ui-btn-secondary' : 'ui-btn-primary'}`}>
          Done
        </button>
      </div>
    </>
  )
}
