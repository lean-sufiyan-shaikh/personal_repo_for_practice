import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { pumpApi } from '../../../api/pumpApi'
import type { FuelType, DUOption } from '../../../types/shift'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { Spinner } from '../../../components/Spinner'
import { useToastStore } from '../../../store/toastStore'
import { codeLabel } from '../../../constants/labels'
import { ALL_FUEL_TYPES } from '../fuelStyles'
import { useInvalidateNozzles } from './nozzlePanels'

interface Props {
  pumpId: number
  du: DUOption
  onClose: () => void
}

export function AddNozzleForm({ pumpId, du, onClose }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  const { addToast } = useToastStore()
  const invalidate = useInvalidateNozzles(pumpId)
  const [inlineNozzleNumber, setInlineNozzleNumber] = useState(String(du.nozzles.length + 1))
  const [inlineFuelType, setInlineFuelType] = useState<FuelType | ''>('')
  const [inlineReading, setInlineReading] = useState('')
  const [inlineError, setInlineError] = useState<string | null>(null)

  const addNozzleMutation = useMutation({
    mutationFn: ({ duId, nozzleNumber, fuelType, initialReading }: { duId: number; nozzleNumber: number; fuelType: FuelType; initialReading?: number }) =>
      pumpApi.addNozzle(pumpId, duId, nozzleNumber, fuelType, initialReading),
    onSuccess: () => {
      onClose()
      addToast(t('nozzles.add.added'), 'success')
      invalidate()
    },
    onError: (err) => { const msg = parseApiError(err, t('nozzles.add.failed')); setInlineError(msg); addToast(msg, 'error') },
  })

  const submitInlineNozzle = (e: React.FormEvent) => {
    e.preventDefault(); setInlineError(null)
    if (!inlineFuelType) { setInlineError(t('nozzles.add.selectFuel')); return }
    if (!inlineNozzleNumber) { setInlineError(t('nozzles.add.enterNumber')); return }
    addNozzleMutation.mutate({
      duId: du.id,
      nozzleNumber: Number(inlineNozzleNumber),
      fuelType: inlineFuelType as FuelType,
      initialReading: inlineReading ? Number(inlineReading) : undefined,
    })
  }

  return (
    <div className="px-4 py-3 border-t border-info bg-info-bg/40 rounded-b-card">
      <form onSubmit={submitInlineNozzle} className="flex items-end gap-2 flex-wrap">
        <div className="w-20">
          <label className="block">
            <span className="ui-label mb-1 text-xs">{t('nozzles.nozzleNumber')}</span>
            <input
              type="number" min="1" max="9"
              value={inlineNozzleNumber}
              onChange={(e) => setInlineNozzleNumber(e.target.value)}
              className="text-xs w-full"
              placeholder="1"
            />
          </label>
        </div>
        <div className="flex-1 min-w-40">
          <span className="ui-label mb-1 text-xs">{t('tanks.fuelType')}</span>
          <SearchableSelect label={t('tanks.fuelType')}
            value={inlineFuelType}
            onChange={(v) => setInlineFuelType(v as FuelType)}
            placeholder={t('nozzles.selectPlaceholder')}
            size="sm"
            options={ALL_FUEL_TYPES.map((ft) => ({ value: ft, label: codeLabel(ft) }))}
          />
        </div>
        <div className="w-32">
          <label className="block">
            <span className="ui-label mb-1 text-xs">{t('nozzles.startReading')}</span>
            <input
              type="number" step="0.001" min="0"
              value={inlineReading}
              onChange={(e) => setInlineReading(e.target.value)}
              placeholder="0"
              className="text-xs w-full"
            />
          </label>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="submit"
            disabled={addNozzleMutation.isPending}
            className="ui-btn ui-btn-primary min-h-0 px-3 py-1.5 text-xs"
          >
            {addNozzleMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />{t('actions.adding')}</span> : t('common:actions.add')}
          </button>
          <button
            type="button"
            onClick={onClose}
            className="ui-btn ui-btn-ghost min-h-0 px-3 py-1.5 text-xs text-ink-3"
          >
            {t('common:actions.cancel')}
          </button>
        </div>
      </form>
      {inlineError && <p role="alert" className="ui-error-text mt-2">{inlineError}</p>}
    </div>
  )
}
