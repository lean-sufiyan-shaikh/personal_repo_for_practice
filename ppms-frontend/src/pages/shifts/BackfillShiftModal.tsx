import { DiscardChangesDialog } from '../../components/DiscardChangesDialog'
import { Modal } from '../../components/Modal'
import type { BackfillShiftModalProps } from './backfill/backfillHelpers'
import { useBackfillForm } from './backfill/useBackfillForm'
import { ShiftDetailsSection } from './backfill/ShiftDetailsSection'
import { StaffNozzlesSection } from './backfill/StaffNozzlesSection'
import { FuelRatesSection } from './backfill/FuelRatesSection'
import { MeterReadingsSection } from './backfill/MeterReadingsSection'
import { CreditSalesSection } from './backfill/CreditSalesSection'
import { PaymentSection } from './backfill/PaymentSection'
import { DiscrepancyReasonSection } from './backfill/DiscrepancyReasonSection'

export default function BackfillShiftModal({ pumpId, onClose }: BackfillShiftModalProps) {
  const form = useBackfillForm({ pumpId, onClose })
  const { error, handleSubmit, isPending, requestClose, isConfirming, discard, keepEditing, canSubmit } = form

  return (
    <>
      <Modal
        title="Backfill Historical Shift"
        subtitle="Record past shift data (up to 365 days back). Tanker deliveries for the shift date must be logged first — the system will block submission if stock is insufficient."
        tone="info"
        size="lg"
        onClose={requestClose}
        canClose={!isPending}
        closeOnBackdropClick
        onSubmit={handleSubmit}
        bodyClassName="space-y-4"
        footer={
          <>
            <button type="button" onClick={requestClose} disabled={isPending} className="ui-btn ui-btn-ghost">
              Cancel
            </button>
            <button
              type="submit"
              disabled={isPending || !canSubmit}
              className="ui-btn ui-btn-primary disabled:opacity-40"
            >
              {isPending ? 'Saving…' : 'Save Historical Shift'}
            </button>
          </>
        }
      >
        <ShiftDetailsSection form={form} />
        <StaffNozzlesSection form={form} />
        <FuelRatesSection form={form} />
        <MeterReadingsSection form={form} />
        <CreditSalesSection form={form} />
        <PaymentSection form={form} />
        <DiscrepancyReasonSection form={form} />
        {error && (
          <div role="alert" className="ui-alert ui-alert-error text-sm">{error}</div>
        )}
      </Modal>
      {isConfirming && <DiscardChangesDialog onDiscard={discard} onKeepEditing={keepEditing} />}
    </>
  )
}
