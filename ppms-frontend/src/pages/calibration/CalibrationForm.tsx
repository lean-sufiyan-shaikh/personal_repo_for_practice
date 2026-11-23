import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { calibrationApi } from '../../api/calibrationApi'
import type { LogCalibrationRequest } from '../../api/calibrationApi'
import type { NozzleDetail } from '../../types/shift'
import { SearchableSelect } from '../../components/SearchableSelect'
import { addDays, localDateInputValue } from '../../utils/date'
import { Reveal } from '../../components/Reveal'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { parseApiError } from '../../utils/apiError'
import { CalibrationReviewRow } from './CalibrationReviewRow'
import { fmtDate } from './calibrationFormat'

interface CalibrationFormProps {
  pumpId: number | null
  nozzles: NozzleDetail[]
  onRecorded: (nozzleId: number) => void
}

function missingDetailError(form: LogCalibrationRequest): string | null {
  if (!form.calibratedBy?.trim()) return 'Calibrated By is required'
  if (!form.calibrationDate) return 'Calibration date is required'
  if (!form.nextCalibrationDue) return 'Next calibration due date is required'
  if (form.nextCalibrationDue <= form.calibrationDate) return 'Next calibration due date must be after the calibration date'
  if (!form.certificateReference?.trim()) return 'Certificate reference is required'
  return null
}

export function CalibrationForm({ pumpId, nozzles, onRecorded }: CalibrationFormProps) {
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [selectedNozzleId, setSelectedNozzleId] = useState<number>(0)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [form, setForm] = useState<LogCalibrationRequest>({
    calibrationDate: localDateInputValue(),
    nextCalibrationDue: null,
    calibratedBy: null,
    certificateReference: null,
    notes: null,
  })
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: LogCalibrationRequest) =>
      calibrationApi.recordCalibration(pumpId!, selectedNozzleId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['calibrations', pumpId] })
      onRecorded(selectedNozzleId)
      setForm({ calibrationDate: localDateInputValue(), nextCalibrationDue: null, calibratedBy: null, certificateReference: null, notes: null })
      setFormError(null)
      setReviewOpen(false)
      addToast('Calibration recorded successfully', 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, 'Failed to record calibration')
      setFormError(msg)
      addToast(msg, 'error')
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedNozzleId) { setFormError('Please select a nozzle'); return }
    const detailError = missingDetailError(form)
    if (detailError) { setFormError(detailError); return }
    setFormError(null)
    setReviewOpen(true)
  }

  const selectedNozzle = nozzles.find((n) => n.id === selectedNozzleId)

  const submitCalibration = () => {
    if (!selectedNozzleId) {
      setFormError('Please select a nozzle')
      return
    }
    const detailError = missingDetailError(form)
    if (detailError) {
      setFormError(detailError)
      return
    }
    createMutation.mutate({
      ...form,
      calibratedBy: form.calibratedBy?.trim() ?? null,
      certificateReference: form.certificateReference?.trim() ?? null,
      notes: form.notes?.trim() || null,
    })
  }

  return (
    <>
      {/* ── Record form ── */}
      <Reveal delay={130}>
      <form onSubmit={handleSubmit} className="ui-card ui-form-shell">
        <div className="ui-form-shell__head">
          <div>
            <p className="ui-section-kicker mb-2">Calibration Entry</p>
            <h2 className="ui-title-sm">Record calibration</h2>
            <p className="ui-subtitle mt-1">Attach the calibration date, technician, certificate and next due date for compliance history.</p>
          </div>
        </div>

        <div className="ui-form-shell__grid">
          <div>
            <span className="ui-label">Nozzle</span>
            <SearchableSelect label="Nozzle"
              value={selectedNozzleId ? selectedNozzleId.toString() : ''}
              onChange={v => { setSelectedNozzleId(parseInt(v) || 0); setFormError(null) }}
              placeholder="Select nozzle…"
              options={nozzles.map((n) => ({
                value: n.id.toString(),
                label: `Nozzle #${n.nozzleNumber} — ${n.fuelType}`,
              }))}
            />
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Calibration Date</span>
              <input
                type="date"
                value={form.calibrationDate}
                onChange={e => setForm(f => ({ ...f, calibrationDate: e.target.value }))}
                className="text-sm"
                required
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Next Calibration Due *</span>
              <input
                type="date"
                value={form.nextCalibrationDue ?? ''}
                min={form.calibrationDate ? addDays(form.calibrationDate, 1) : undefined}
                onChange={e => setForm(f => ({ ...f, nextCalibrationDue: e.target.value || null }))}
                className="text-sm"
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Calibrated By *</span>
              <input
                type="text"
                value={form.calibratedBy ?? ''}
                onChange={e => setForm(f => ({ ...f, calibratedBy: e.target.value || null }))}
                className="text-sm"
                placeholder="Technician or agency name"
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Certificate Reference *</span>
              <input
                type="text"
                value={form.certificateReference ?? ''}
                onChange={e => setForm(f => ({ ...f, certificateReference: e.target.value || null }))}
                className="text-sm"
                placeholder="Inspection certificate number"
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">Notes (optional)</span>
              <input
                type="text"
                value={form.notes ?? ''}
                onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
                className="text-sm"
                placeholder="Additional observations"
              />
            </label>
          </div>
        </div>

        {formError && <p role="alert" className="ui-error-text">{formError}</p>}

        <button
          type="submit"
          disabled={createMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {createMutation.isPending
          ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />Saving…</span>
          : 'Review Calibration'}
        </button>
      </form>
      </Reveal>

      {reviewOpen && (
        <Modal
          title="Review Calibration"
          subtitle="Verify the nozzle and calibration details before saving."
          tone="info"
          onClose={() => setReviewOpen(false)}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={<>
            <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-secondary">
              Back
            </button>
            <button
              onClick={submitCalibration}
              disabled={createMutation.isPending}
              className="ui-btn ui-btn-primary"
            >
              {createMutation.isPending ? 'Saving…' : 'Confirm Calibration'}
            </button>
          </>}
        >
          {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{formError} Go back to modify the data and try again.</div>}
          <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
            <CalibrationReviewRow
              label="Nozzle"
              value={selectedNozzle
                ? `Nozzle #${selectedNozzle.nozzleNumber} — ${selectedNozzle.fuelType}`
                : '—'}
            />
            <CalibrationReviewRow label="Calibration Date" value={fmtDate(form.calibrationDate)} />
            <CalibrationReviewRow label="Next Calibration Due" value={form.nextCalibrationDue ? fmtDate(form.nextCalibrationDue) : '—'} />
            <CalibrationReviewRow label="Calibrated By" value={form.calibratedBy?.trim() || '—'} />
            <CalibrationReviewRow label="Certificate Reference" value={form.certificateReference?.trim() || '—'} />
            <CalibrationReviewRow label="Notes" value={form.notes?.trim() || '—'} />
          </div>
        </Modal>
      )}
    </>
  )
}
