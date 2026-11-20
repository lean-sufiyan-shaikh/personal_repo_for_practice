import { ModalPortal } from '../../components/ModalPortal'
import type { BackfillShiftModalProps } from './backfill/backfillHelpers'
import { useBackfillForm } from './backfill/useBackfillForm'
import { ShiftDetailsSection } from './backfill/ShiftDetailsSection'
import { StaffNozzlesSection } from './backfill/StaffNozzlesSection'
import { FuelRatesSection } from './backfill/FuelRatesSection'
import { MeterReadingsSection } from './backfill/MeterReadingsSection'
import { CreditSalesSection } from './backfill/CreditSalesSection'
import { PaymentSection } from './backfill/PaymentSection'
import { DiscrepancyReasonSection } from './backfill/DiscrepancyReasonSection'

// ── Component ─────────────────────────────────────────────────────────────────

export default function BackfillShiftModal({ pumpId, onClose }: BackfillShiftModalProps) {
  const form = useBackfillForm({ pumpId, onClose })
  const { error, handleSubmit, isPending, requestClose, canSubmit } = form

  return (
    <ModalPortal onClose={isPending ? undefined : requestClose}>
      <div className="ui-modal-backdrop" onClick={!isPending ? requestClose : undefined}>
        <div
          className="ui-modal-panel w-full max-w-xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* ── Header ──────────────────────────────────────────────────── */}
          <div
            className="ui-modal-header ui-modal-header--themed"
            style={{ background: 'linear-gradient(135deg, #7c3aed 0%, #5b21b6 100%)' }}
          >
            <div className="ui-modal-heading">
              <h2 className="ui-modal-title">Backfill Historical Shift</h2>
              <p className="ui-modal-subtitle">
                Record past shift data (up to 365 days back). Tanker deliveries for the shift date must be logged first — the system will block submission if stock is insufficient.
              </p>
            </div>
            <button aria-label="Close" type="button" onClick={requestClose} className="ui-btn ui-btn-ghost ui-modal-close" disabled={isPending}>
              ×
            </button>
          </div>

          {/* ── Scrollable form ──────────────────────────────────────────── */}
          <form onSubmit={handleSubmit} className="flex flex-col flex-1 min-h-0 overflow-hidden">
            <div className="ui-modal-body space-y-4">

              {/* ── Shift Details ──────────────────────────────────────── */}
              <ShiftDetailsSection form={form} />


              {/* ── Staff & Nozzles ────────────────────────────────────── */}
              <StaffNozzlesSection form={form} />


              {/* ── Fuel Rates ─────────────────────────────────────────── */}
              <FuelRatesSection form={form} />


              {/* ── Meter Readings ─────────────────────────────────────── */}
              <MeterReadingsSection form={form} />


              {/* ── Credit Sales ───────────────────────────────────────── */}
              <CreditSalesSection form={form} />


              {/* ── Payment Collection ─────────────────────────────────── */}
              <PaymentSection form={form} />


              {/* ── Discrepancy reason ──────────────────────────────────── */}
              <DiscrepancyReasonSection form={form} />


              {error && (
                <div className="ui-alert ui-alert-error text-sm">{error}</div>
              )}
            </div>

            {/* ── Footer ────────────────────────────────────────────────── */}
            <div className="ui-modal-footer">
              <button type="button" onClick={requestClose} disabled={isPending} className="ui-btn ui-btn-ghost">
                Cancel
              </button>
              <button
                type="submit"
                disabled={isPending || !canSubmit}
                className="ui-btn ui-btn-primary disabled:opacity-40"
                style={!isPending ? { background: 'linear-gradient(135deg, #7c3aed 0%, #5b21b6 100%)', border: 'none' } : {}}
              >
                {isPending ? 'Saving…' : 'Save Historical Shift'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </ModalPortal>
  )
}
