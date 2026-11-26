import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ShieldCheck, Truck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../utils/apiError'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary, UpdatePumpSettingsRequest } from '../../api/pumpApi'
import { useToastStore } from '../../store/toastStore'
import { FormField } from '../../components/FormField'
import { FormGrid } from '../../components/layout/FormGrid'
import { NumericInput } from '../../components/NumericInput'
import { formatCurrency, formatDecimal } from '../../utils/format'
import { DEFAULT_DENSITY_TOLERANCE, DEFAULT_RECEIPT_TOLERANCE_PERCENT } from '../inventory/record-delivery/receiptCheck'
import { SetupFormGroup } from './SetupFormGroup'
import { SetupSaveBar } from './SetupSaveBar'

const MAX_RECEIPT_TOLERANCE_PERCENT = 5
const MAX_DENSITY_TOLERANCE = 50

interface SettingsText {
  threshold: string
  receiptTolerance: string
  densityTolerance: string
}

function textOf(pump: PumpSummary): SettingsText {
  return {
    threshold: pump.discrepancyEscalationThreshold?.toString() ?? '',
    receiptTolerance: String(pump.receiptTolerancePercent ?? DEFAULT_RECEIPT_TOLERANCE_PERCENT),
    densityTolerance: String(pump.densityTolerance ?? DEFAULT_DENSITY_TOLERANCE),
  }
}

function inRange(text: string, max: number): boolean {
  const value = Number(text)
  return text.trim() !== '' && Number.isFinite(value) && value > 0 && value <= max
}

export function PumpSettingsContent({ pump, onUpdated }: { pump: PumpSummary; onUpdated: () => void }) {
  const { t } = useTranslation(['setup', 'common'])
  const { addToast } = useToastStore()
  const [saved, setSaved] = useState<SettingsText>(() => textOf(pump))
  const [values, setValues] = useState<SettingsText>(saved)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [error, setError]         = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: UpdatePumpSettingsRequest) => pumpApi.updatePumpSettings(pump.id, req),
    onSuccess: () => {
      addToast(t('settings.saved'), 'success')
      setError(null)
      setSaved(values)
      onUpdated()
    },
    onError: (err) => setError(parseApiError(err, t('settings.saveFailed'))),
  })

  const change = (field: keyof SettingsText, value: string) => {
    setValues(prev => ({ ...prev, [field]: value }))
    if (field === 'threshold') setFieldError(null)
    setError(null)
  }

  const receiptToleranceValid = inRange(values.receiptTolerance, MAX_RECEIPT_TOLERANCE_PERCENT)
  const densityToleranceValid = inRange(values.densityTolerance, MAX_DENSITY_TOLERANCE)

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    const req: UpdatePumpSettingsRequest = {}
    const threshold = values.threshold.trim()
    if (threshold !== saved.threshold.trim()) {
      const num = threshold === '' ? null : parseFloat(threshold)
      if (num !== null && (isNaN(num) || num < 0)) {
        setFieldError(t('settings.invalidAmount'))
        return
      }
      req.discrepancyEscalationThreshold = num
    }
    if (values.receiptTolerance !== saved.receiptTolerance) req.receiptTolerancePercent = Number(values.receiptTolerance)
    if (values.densityTolerance !== saved.densityTolerance) req.densityTolerance = Number(values.densityTolerance)
    mutation.mutate(req)
  }

  const typedLimit = Number(values.threshold)
  const effect = values.threshold.trim() === ''
    ? t('settings.effectOff')
    : Number.isFinite(typedLimit) && typedLimit >= 0
    ? t('settings.effectOn', { amount: formatCurrency(typedLimit) })
    : null
  const hasChanges = values.threshold.trim() !== saved.threshold.trim()
    || values.receiptTolerance !== saved.receiptTolerance || values.densityTolerance !== saved.densityTolerance

  return (
    <form onSubmit={handleSave} className="space-y-4">
      <SetupFormGroup icon={ShieldCheck} title={t('settings.escalationTitle')} hint={t('settings.explanation')}>
        <FormGrid>
          <FormField
            label={<>{t('settings.thresholdLabel')} <span className="text-ink-3 font-normal">{t('common:fields.optionalSuffix')}</span></>}
            help={effect ?? undefined}
            error={fieldError ?? undefined}
          >
            <NumericInput min="0" step="1" unit="₹" value={values.threshold}
              onChange={(e) => change('threshold', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>

      <SetupFormGroup icon={Truck} title={t('settings.receiptTitle')} hint={t('settings.receiptExplanation')}>
        <FormGrid>
          <FormField
            label={t('settings.receiptToleranceLabel')}
            help={receiptToleranceValid ? t('settings.receiptToleranceEffect', { percent: formatDecimal(Number(values.receiptTolerance)) }) : undefined}
            error={receiptToleranceValid ? undefined : t('settings.receiptToleranceInvalid', { max: MAX_RECEIPT_TOLERANCE_PERCENT })}
          >
            <NumericInput min="0.01" max={MAX_RECEIPT_TOLERANCE_PERCENT} step="0.01" unit="%" value={values.receiptTolerance}
              onChange={(e) => change('receiptTolerance', e.target.value)} />
          </FormField>
          <FormField
            label={t('settings.densityToleranceLabel')}
            help={densityToleranceValid ? t('settings.densityToleranceEffect', { tolerance: formatDecimal(Number(values.densityTolerance), 1) }) : undefined}
            error={densityToleranceValid ? undefined : t('settings.densityToleranceInvalid', { max: MAX_DENSITY_TOLERANCE })}
          >
            <NumericInput min="0.1" max={MAX_DENSITY_TOLERANCE} step="0.1" unit="kg/m³" value={values.densityTolerance}
              onChange={(e) => change('densityTolerance', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>

      <SetupSaveBar hasChanges={hasChanges && receiptToleranceValid && densityToleranceValid} isSaving={mutation.isPending}
        saveLabel={t('settings.save')} error={error} />
    </form>
  )
}
