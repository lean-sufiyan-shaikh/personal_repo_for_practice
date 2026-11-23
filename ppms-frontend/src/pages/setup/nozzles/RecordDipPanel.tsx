import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { localDateInputValue } from '../../../utils/date'
import { dipApi } from '../../../api/dipApi'
import type { TankInfo } from '../../../api/pumpApi'
import type { NozzleDetail } from '../../../types/shift'
import { Spinner } from '../../../components/Spinner'
import { useToastStore } from '../../../store/toastStore'
import { codeLabel } from '../../../constants/labels'
import { formatNumberIn } from '../../../i18n/format'
import { FUEL_COLOR } from '../fuelStyles'
import { useInvalidateNozzles } from './nozzlePanels'

interface Props {
  pumpId: number
  nozzle: NozzleDetail
  /** The tank the nozzle draws from — drained fuel leaves this tank's stock. */
  tank: TankInfo | null
  onClose: () => void
}

export function RecordDipPanel({ pumpId, nozzle, tank, onClose }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  const { addToast } = useToastStore()
  const queryClient = useQueryClient()
  const invalidate = useInvalidateNozzles(pumpId)
  const [dipLitres, setDipLitres] = useState('')
  const dipDate = localDateInputValue()
  const [dipReason, setDipReason] = useState('')
  const [dipError, setDipError] = useState<string | null>(null)

  const recordDipMutation = useMutation({
    mutationFn: ({ tankId, litresRemoved, reason, date }: { tankId: number; litresRemoved: number; reason: string; date: string }) =>
      dipApi.recordDip(pumpId, tankId, litresRemoved, reason, date),
    onSuccess: () => {
      onClose()
      addToast(t('nozzles.dip.recorded'), 'success')
      invalidate()
      queryClient.invalidateQueries({ queryKey: ['tanks', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pumpId] })
    },
    onError: (err) => { const msg = parseApiError(err, t('nozzles.dip.failed')); setDipError(msg); addToast(msg, 'error') },
  })

  const submitDip = (e: React.FormEvent) => {
    e.preventDefault(); setDipError(null)
    if (!tank) { setDipError(t('nozzles.dip.mapFirst')); return }
    if (!dipLitres || Number(dipLitres) <= 0) { setDipError(t('nozzles.dip.invalidLitres')); return }
    if (Number(dipLitres) > tank.currentStock) { setDipError(t('nozzles.dip.tooMuch', { name: tank.tankIdentifier, stock: tank.currentStock })); return }
    if (!dipReason.trim()) { setDipError(t('nozzles.reasonRequired')); return }
    recordDipMutation.mutate({
      tankId: tank.id,
      litresRemoved: Number(dipLitres),
      reason: dipReason,
      date: dipDate,
    })
  }

  return (
    <div className="px-4 py-3 border-t border-border">
      <form onSubmit={submitDip} className="ui-dip-editor">
        <div className="ui-dip-editor__hero">
          <div>
            <p className="ui-section-kicker mb-2">{t('nozzles.dip.kicker')}</p>
            <h3 className="ui-title-sm">{t('nozzles.dip.title')}</h3>
            <p className="ui-subtitle mt-1">
              {t('nozzles.dip.subtitle')}
            </p>
          </div>
          <div className="ui-dip-editor__meta">
            <span className={`text-xs px-2 py-0.5 rounded-badge font-medium border ${FUEL_COLOR[nozzle.fuelType] ?? ''}`}>
              {codeLabel(nozzle.fuelType)}
            </span>
          </div>
        </div>
        <p className="text-xs text-ink-3">
          {tank
            ? t('nozzles.dip.fromTank', { name: tank.tankIdentifier, stock: formatNumberIn(tank.currentStock, { minimumFractionDigits: 1 }) })
            : t('nozzles.dip.notMapped')}
        </p>
        <div className="ui-dip-editor__section">
          <label className="block">
            <span className="ui-label">{t('nozzles.dip.litresRemoved')}</span>
            <input
              type="number"
              step="0.001"
              min="0.001"
              value={dipLitres}
              onChange={(e) => setDipLitres(e.target.value)}
              placeholder={t('example', { value: '12.500' })}
              className="text-xs min-h-10"
            />
          </label>
        </div>
        <div className="ui-dip-editor__grid">
          <div className="ui-dip-editor__section">
            <label className="block">
              <span className="ui-label">{t('nozzles.dip.date')}</span>
              <input
                type="date"
                value={dipDate}
                readOnly
                className="text-xs min-h-10 bg-bg"
              />
              <span className="block mt-1 text-xs text-ink-3">
                {t('nozzles.dip.todayOnly')}
              </span>
            </label>
          </div>
          <div className="ui-dip-editor__section">
            <label className="block">
              <span className="ui-label">{t('common:fields.reason')} <span className="text-bad">*</span></span>
              <input
                type="text"
                value={dipReason}
                onChange={(e) => setDipReason(e.target.value)}
                placeholder={t('nozzles.dip.reasonPlaceholder')}
                className="text-xs min-h-10"
              />
            </label>
          </div>
        </div>
        {dipError && <p role="alert" className="ui-error-text">{dipError}</p>}
        <div className="ui-dip-editor__actions">
          <button
            type="submit"
            disabled={recordDipMutation.isPending || !tank}
            className="ui-btn ui-btn-warning"
          >
            {recordDipMutation.isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-3 h-3" />{t('nozzles.dip.recording')}</span> : t('nozzles.row.recordDip')}
          </button>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
        </div>
      </form>    </div>
  )
}
