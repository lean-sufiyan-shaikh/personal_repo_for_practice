import { useState } from 'react'
import { codeLabel } from '../../constants/labels'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
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

function missingDetailError(form: LogCalibrationRequest, t: TFunction<['calibration', 'common']>): string | null {
  if (!form.calibratedBy?.trim()) return t('validation.calibratedByRequired')
  if (!form.calibrationDate) return t('validation.dateRequired')
  if (!form.nextCalibrationDue) return t('validation.nextDueRequired')
  if (form.nextCalibrationDue <= form.calibrationDate) return t('validation.nextDueAfterDate')
  if (!form.certificateReference?.trim()) return t('validation.certificateRequired')
  return null
}

export function CalibrationForm({ pumpId, nozzles, onRecorded }: CalibrationFormProps) {
  const { t } = useTranslation(['calibration', 'common'])
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
      addToast(t('toast.recorded'), 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, t('toast.recordFailed'))
      setFormError(msg)
      addToast(msg, 'error')
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedNozzleId) { setFormError(t('validation.selectNozzle')); return }
    const detailError = missingDetailError(form, t)
    if (detailError) { setFormError(detailError); return }
    setFormError(null)
    setReviewOpen(true)
  }

  const selectedNozzle = nozzles.find((n) => n.id === selectedNozzleId)

  const submitCalibration = () => {
    if (!selectedNozzleId) {
      setFormError(t('validation.selectNozzle'))
      return
    }
    const detailError = missingDetailError(form, t)
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
            <p className="ui-section-kicker mb-2">{t('form.kicker')}</p>
            <h2 className="ui-title-sm">{t('form.title')}</h2>
            <p className="ui-subtitle mt-1">{t('form.subtitle')}</p>
          </div>
        </div>

        <div className="ui-form-shell__grid">
          <div>
            <span className="ui-label">{t('form.nozzle')}</span>
            <SearchableSelect label={t('form.nozzle')}
              value={selectedNozzleId ? selectedNozzleId.toString() : ''}
              onChange={v => { setSelectedNozzleId(parseInt(v) || 0); setFormError(null) }}
              placeholder={t('form.nozzlePlaceholder')}
              options={nozzles.map((n) => ({
                value: n.id.toString(),
                label: t('nozzleOption', { number: n.nozzleNumber, fuel: codeLabel(n.fuelType) }),
              }))}
            />
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('form.calibrationDate')}</span>
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
              <span className="ui-label">{t('form.nextDueRequired')}</span>
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
              <span className="ui-label">{t('form.calibratedByRequired')}</span>
              <input
                type="text"
                value={form.calibratedBy ?? ''}
                onChange={e => setForm(f => ({ ...f, calibratedBy: e.target.value || null }))}
                className="text-sm"
                placeholder={t('form.calibratedByPlaceholder')}
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('form.certificateRequired')}</span>
              <input
                type="text"
                value={form.certificateReference ?? ''}
                onChange={e => setForm(f => ({ ...f, certificateReference: e.target.value || null }))}
                className="text-sm"
                placeholder={t('form.certificatePlaceholder')}
              />
            </label>
          </div>

          <div>
            <label className="block">
              <span className="ui-label">{t('form.notesOptional')}</span>
              <input
                type="text"
                value={form.notes ?? ''}
                onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
                className="text-sm"
                placeholder={t('form.notesPlaceholder')}
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
          ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('common:actions.saving')}</span>
          : t('form.review')}
        </button>
      </form>
      </Reveal>

      {reviewOpen && (
        <Modal
          title={t('review.title')}
          subtitle={t('review.subtitle')}
          tone="info"
          onClose={() => setReviewOpen(false)}
          closeOnBackdropClick
          bodyClassName="space-y-4"
          footer={<>
            <button onClick={() => setReviewOpen(false)} className="ui-btn ui-btn-secondary">
              {t('common:actions.back')}
            </button>
            <button
              onClick={submitCalibration}
              disabled={createMutation.isPending}
              className="ui-btn ui-btn-primary"
            >
              {createMutation.isPending ? t('common:actions.saving') : t('review.confirm')}
            </button>
          </>}
        >
          {formError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{t('common:notices.goBackRetry', { error: formError })}</div>}
          <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
            <CalibrationReviewRow
              label={t('review.nozzle')}
              value={selectedNozzle
                ? t('nozzleOption', { number: selectedNozzle.nozzleNumber, fuel: codeLabel(selectedNozzle.fuelType) })
                : '—'}
            />
            <CalibrationReviewRow label={t('review.calibrationDate')} value={fmtDate(form.calibrationDate)} />
            <CalibrationReviewRow label={t('review.nextDue')} value={form.nextCalibrationDue ? fmtDate(form.nextCalibrationDue) : '—'} />
            <CalibrationReviewRow label={t('review.calibratedBy')} value={form.calibratedBy?.trim() || '—'} />
            <CalibrationReviewRow label={t('review.certificate')} value={form.certificateReference?.trim() || '—'} />
            <CalibrationReviewRow label={t('common:fields.notes')} value={form.notes?.trim() || '—'} />
          </div>
        </Modal>
      )}
    </>
  )
}
