import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { dipApi } from '../../../api/dipApi'
import type { NozzleDetail } from '../../../types/shift'
import { Spinner } from '../../../components/Spinner'
import { useToastStore } from '../../../store/toastStore'
import { FUEL_UNIT } from '../../../constants/fuel'
import { useInvalidateNozzles } from './nozzlePanels'

interface Props {
  pumpId: number
  nozzle: NozzleDetail
  onClose: () => void
}

export function AdjustReadingPanel({ pumpId, nozzle, onClose }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  const { addToast } = useToastStore()
  const invalidate = useInvalidateNozzles(pumpId)
  const [adjustType, setAdjustType] = useState<'RESET' | 'CUSTOM_READING'>('CUSTOM_READING')
  const [adjustReading, setAdjustReading] = useState('')
  const [adjustReason, setAdjustReason] = useState('')
  const [adjustError, setAdjustError] = useState<string | null>(null)

  const recordAdjustmentMutation = useMutation({
    mutationFn: ({ nozzleId, adjustmentType, reason, newReading }: { nozzleId: number; adjustmentType: 'RESET' | 'CUSTOM_READING'; reason: string; newReading?: number }) =>
      dipApi.recordAdjustment(pumpId, nozzleId, adjustmentType, reason, newReading),
    onSuccess: () => { onClose(); addToast(t('nozzles.reading.adjusted'), 'success'); invalidate() },
    onError: (err) => { const msg = parseApiError(err, t('nozzles.reading.failed')); setAdjustError(msg); addToast(msg, 'error') },
  })

  const submitAdjustment = async (e: React.FormEvent) => {
    e.preventDefault(); setAdjustError(null)
    if (!adjustReason.trim()) { setAdjustError(t('nozzles.reasonRequired')); return }
    if (adjustType === 'CUSTOM_READING') {
      if (!adjustReading) { setAdjustError(t('nozzles.reading.enterReading')); return }
      await recordAdjustmentMutation.mutateAsync({
        nozzleId: nozzle.id,
        adjustmentType: 'CUSTOM_READING',
        reason: adjustReason,
        newReading: Number(adjustReading),
      })
    } else {
      await recordAdjustmentMutation.mutateAsync({
        nozzleId: nozzle.id,
        adjustmentType: 'RESET',
        reason: adjustReason,
      })
    }
  }

  return (
    <div className="px-4 py-3 border-t border-border">
      <form onSubmit={submitAdjustment} className="ui-reading-editor">
        <div className="ui-reading-editor__hero">
          <div>
            <p className="ui-section-kicker mb-2">{t('nozzles.reading.kicker')}</p>
            <h3 className="ui-title-sm">{t('nozzles.reading.title')}</h3>
            <p className="ui-subtitle mt-1">
              {t('nozzles.reading.subtitle', { number: nozzle.nozzleNumber })}
            </p>
          </div>
        </div>
        <div className="ui-alert ui-alert-warning text-xs">
          {t('nozzles.reading.activeShiftWarning')}
        </div>
        <div className="ui-reading-editor__section">
          <div className="ui-reading-editor__section-head">
            <div>
              <p className="ui-label mb-1">{t('nozzles.reading.type')}</p>
              <p className="ui-help mt-0">{t('nozzles.reading.typeHint')}</p>
            </div>
          </div>
          <div className="ui-reading-editor__mode-grid">
            <button
              type="button"
              onClick={() => setAdjustType('CUSTOM_READING')}
              className={`ui-reading-editor__mode-card ${adjustType === 'CUSTOM_READING' ? 'ui-reading-editor__mode-card--active ui-reading-editor__mode-card--custom' : ''}`}
            >
              <span className="ui-reading-editor__mode-title">{t('nozzles.reading.custom')}</span>
              <span className="ui-reading-editor__mode-copy">{t('nozzles.reading.customHint')}</span>
            </button>
            <button
              type="button"
              onClick={() => setAdjustType('RESET')}
              className={`ui-reading-editor__mode-card ${adjustType === 'RESET' ? 'ui-reading-editor__mode-card--active ui-reading-editor__mode-card--danger' : ''}`}
            >
              <span className="ui-reading-editor__mode-title">{t('nozzles.reading.reset')}</span>
              <span className="ui-reading-editor__mode-copy">{t('nozzles.reading.resetHint')}</span>
            </button>
          </div>
        </div>
        {adjustType === 'CUSTOM_READING' && (
          <div className="ui-reading-editor__section">
            <label className="block">
              <span className="ui-label">
              {t('nozzles.reading.newReading')} <span className="ui-help mt-0">{t('nozzles.reading.current', { reading: nozzle.lastReading, unit: FUEL_UNIT[nozzle.fuelType] })}</span>
            </span>
              <input
                type="number"
                step="0.001"
                min="0"
                value={adjustReading}
                onChange={(e) => setAdjustReading(e.target.value)}
                placeholder={t('nozzles.reading.newReading')}
                className="text-sm"
              />
            </label>
          </div>
        )}
        {adjustType === 'RESET' && (
          <div className="ui-reading-editor__section">
            <div className="ui-alert ui-alert-danger">
              <p className="text-xs text-bad">
                {t('nozzles.reading.resetWarningBefore', { number: nozzle.nozzleNumber })} <strong>0.00</strong>{t('nozzles.reading.resetWarningAfter')}
              </p>
            </div>
          </div>
        )}
        <div className="ui-reading-editor__section">
          <label className="block">
            <span className="ui-label">{t('nozzles.reason')} <span className="text-bad">*</span></span>
            <input
              type="text"
              value={adjustReason}
              onChange={(e) => setAdjustReason(e.target.value)}
              placeholder={t('nozzles.reading.reasonPlaceholder')}
              className="text-sm"
            />
          </label>
          <p className="ui-help">{t('nozzles.reading.reasonHint')}</p>
        </div>
        {adjustError && <p role="alert" className="ui-error-text">{adjustError}</p>}
        <div className="ui-reading-editor__actions">
          <button
            type="submit"
            disabled={recordAdjustmentMutation.isPending}
            className={`ui-btn ${adjustType === 'RESET' ? 'ui-btn-danger' : 'ui-btn-primary'}`}
          >
            {recordAdjustmentMutation.isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-3 h-3" />{t('actions.saving')}</span> : adjustType === 'RESET' ? t('nozzles.reading.resetSubmit') : t('nozzles.reading.saveSubmit')}
          </button>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
        </div>
      </form>    </div>
  )
}
