import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Clock, ShieldCheck, Truck, Vault } from 'lucide-react'
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
import { TestLitresSettingsGroup } from './TestLitresSettingsGroup'
import {
  DEFAULT_MAX_TEST_LITRES_PER_NOZZLE, DEFAULT_TEST_VOLUME_LITRES, MAX_TEST_LITRES_LIMIT, MAX_TEST_VOLUME_LITRES, litresInRange,
} from './testLitresSettings'

const MAX_RECEIPT_TOLERANCE_PERCENT = 5
const MAX_DENSITY_TOLERANCE = 50
const MAX_SAFE_COUNT_TOLERANCE = 10_000
const MAX_ESCALATION_THRESHOLD = 10_000_000
const MAX_ROUNDING_TOLERANCE = 100
const MAX_EARLY_START_GRACE_MINUTES = 120
const DEFAULT_ESCALATION_THRESHOLD = 500
const DEFAULT_ROUNDING_TOLERANCE = 1
const DEFAULT_EARLY_START_GRACE_MINUTES = 30
const DEFAULT_SAFE_COUNT_TOLERANCE = 10
const DEFAULT_SAFE_COUNT_CUTOFF = '10:00'
const TIME_OF_DAY = /^([01]\d|2[0-3]):[0-5]\d$/

interface SettingsText {
  threshold: string
  roundingTolerance: string
  earlyStartGrace: string
  receiptTolerance: string
  densityTolerance: string
  safeCountTolerance: string
  safeCountCutoff: string
  testVolume: string
  maxTestLitres: string
  /** 'true' or 'false', kept as text like every other setting while it is edited. */
  blindCashCount: string
}

function textOf(pump: PumpSummary): SettingsText {
  return {
    threshold: String(pump.discrepancyEscalationThreshold ?? DEFAULT_ESCALATION_THRESHOLD),
    roundingTolerance: String(pump.roundingTolerance ?? DEFAULT_ROUNDING_TOLERANCE),
    earlyStartGrace: String(pump.earlyStartGraceMinutes ?? DEFAULT_EARLY_START_GRACE_MINUTES),
    receiptTolerance: String(pump.receiptTolerancePercent ?? DEFAULT_RECEIPT_TOLERANCE_PERCENT),
    densityTolerance: String(pump.densityTolerance ?? DEFAULT_DENSITY_TOLERANCE),
    safeCountTolerance: String(pump.safeCountTolerance ?? DEFAULT_SAFE_COUNT_TOLERANCE),
    safeCountCutoff: pump.safeCountCutoffTime?.slice(0, 5) ?? DEFAULT_SAFE_COUNT_CUTOFF,
    testVolume: String(pump.testVolumeLitres ?? DEFAULT_TEST_VOLUME_LITRES),
    maxTestLitres: String(pump.maxTestLitresPerNozzle ?? DEFAULT_MAX_TEST_LITRES_PER_NOZZLE),
    blindCashCount: String(pump.blindCashCount ?? true),
  }
}

function inRange(text: string, max: number): boolean {
  const value = Number(text)
  return text.trim() !== '' && Number.isFinite(value) && value > 0 && value <= max
}

function inRangeFromZero(text: string, max: number): boolean {
  const value = Number(text)
  return text.trim() !== '' && Number.isFinite(value) && value >= 0 && value <= max
}

export function PumpSettingsContent({ pump, onUpdated }: { pump: PumpSummary; onUpdated: () => void }) {
  const { t } = useTranslation(['setup', 'common'])
  const { addToast } = useToastStore()
  const [saved, setSaved] = useState<SettingsText>(() => textOf(pump))
  const [values, setValues] = useState<SettingsText>(saved)
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
    setError(null)
  }

  const thresholdValid = inRangeFromZero(values.threshold, MAX_ESCALATION_THRESHOLD)
  const roundingValid = inRangeFromZero(values.roundingTolerance, MAX_ROUNDING_TOLERANCE)
  const graceValid = inRangeFromZero(values.earlyStartGrace, MAX_EARLY_START_GRACE_MINUTES)
    && Number.isInteger(Number(values.earlyStartGrace))
  const receiptToleranceValid = inRange(values.receiptTolerance, MAX_RECEIPT_TOLERANCE_PERCENT)
  const densityToleranceValid = inRange(values.densityTolerance, MAX_DENSITY_TOLERANCE)
  const safeToleranceValid = inRangeFromZero(values.safeCountTolerance, MAX_SAFE_COUNT_TOLERANCE)
  const safeCutoffValid = TIME_OF_DAY.test(values.safeCountCutoff)
  const allValid = thresholdValid && roundingValid && graceValid && receiptToleranceValid && densityToleranceValid
    && safeToleranceValid && safeCutoffValid && litresInRange(values.testVolume, MAX_TEST_VOLUME_LITRES)
    && litresInRange(values.maxTestLitres, MAX_TEST_LITRES_LIMIT)

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    const req: UpdatePumpSettingsRequest = {}
    if (values.threshold !== saved.threshold) req.discrepancyEscalationThreshold = Number(values.threshold)
    if (values.roundingTolerance !== saved.roundingTolerance) req.roundingTolerance = Number(values.roundingTolerance)
    if (values.earlyStartGrace !== saved.earlyStartGrace) req.earlyStartGraceMinutes = Number(values.earlyStartGrace)
    if (values.receiptTolerance !== saved.receiptTolerance) req.receiptTolerancePercent = Number(values.receiptTolerance)
    if (values.densityTolerance !== saved.densityTolerance) req.densityTolerance = Number(values.densityTolerance)
    if (values.safeCountTolerance !== saved.safeCountTolerance) req.safeCountTolerance = Number(values.safeCountTolerance)
    if (values.safeCountCutoff !== saved.safeCountCutoff) req.safeCountCutoffTime = values.safeCountCutoff
    if (values.testVolume !== saved.testVolume) req.testVolumeLitres = Number(values.testVolume)
    if (values.maxTestLitres !== saved.maxTestLitres) req.maxTestLitresPerNozzle = Number(values.maxTestLitres)
    if (values.blindCashCount !== saved.blindCashCount) req.blindCashCount = values.blindCashCount === 'true'
    mutation.mutate(req)
  }

  const typedLimit = Number(values.threshold)
  const thresholdEffect = typedLimit === 0
    ? t('settings.effectAll')
    : t('settings.effectOn', { amount: formatCurrency(typedLimit) })
  const hasChanges = (Object.keys(values) as Array<keyof SettingsText>).some((field) => values[field] !== saved[field])

  return (
    <form onSubmit={handleSave} className="space-y-4">
      <SetupFormGroup icon={ShieldCheck} title={t('settings.escalationTitle')} hint={t('settings.explanation')}>
        <FormGrid>
          <FormField label={t('settings.thresholdLabel')}
            help={thresholdValid ? thresholdEffect : undefined}
            error={thresholdValid ? undefined : t('settings.invalidAmount')}>
            <NumericInput min="0" step="1" unit="₹" value={values.threshold}
              onChange={(e) => change('threshold', e.target.value)} />
          </FormField>
          <FormField label={t('settings.roundingLabel')}
            help={roundingValid ? t('settings.roundingEffect', { amount: formatCurrency(Number(values.roundingTolerance)) }) : undefined}
            error={roundingValid ? undefined : t('settings.roundingInvalid', { max: formatCurrency(MAX_ROUNDING_TOLERANCE) })}>
            <NumericInput min="0" max={MAX_ROUNDING_TOLERANCE} step="0.01" unit="₹" value={values.roundingTolerance}
              onChange={(e) => change('roundingTolerance', e.target.value)} />
          </FormField>
        </FormGrid>
        <label className="mt-3 flex items-start gap-2 text-sm text-ink">
          <input type="checkbox" checked={values.blindCashCount === 'true'}
            onChange={(e) => change('blindCashCount', String(e.target.checked))} />
          <span>
            <span className="block font-medium">{t('settings.blindCountLabel')}</span>
            <span className="block text-xs text-ink-3">{t('settings.blindCountHelp')}</span>
          </span>
        </label>
      </SetupFormGroup>

      <SetupFormGroup icon={Clock} title={t('settings.graceTitle')} hint={t('settings.graceExplanation')}>
        <FormGrid>
          <FormField label={t('settings.graceLabel')}
            help={graceValid ? t('settings.graceEffect', { count: Number(values.earlyStartGrace) }) : undefined}
            error={graceValid ? undefined : t('settings.graceInvalid', { max: MAX_EARLY_START_GRACE_MINUTES })}>
            <NumericInput min="0" max={MAX_EARLY_START_GRACE_MINUTES} step="1" unit={t('settings.minutesUnit')}
              value={values.earlyStartGrace} onChange={(e) => change('earlyStartGrace', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>

      <TestLitresSettingsGroup testVolume={values.testVolume} maxTestLitres={values.maxTestLitres}
        onTestVolumeChange={(value) => change('testVolume', value)}
        onMaxTestLitresChange={(value) => change('maxTestLitres', value)} />

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

      <SetupFormGroup icon={Vault} title={t('settings.safeTitle')} hint={t('settings.safeExplanation')}>
        <FormGrid>
          <FormField
            label={t('settings.safeToleranceLabel')}
            help={safeToleranceValid ? t('settings.safeToleranceEffect', { amount: formatCurrency(Number(values.safeCountTolerance)) }) : undefined}
            error={safeToleranceValid ? undefined : t('settings.safeToleranceInvalid', { max: formatCurrency(MAX_SAFE_COUNT_TOLERANCE) })}
          >
            <NumericInput min="0" max={MAX_SAFE_COUNT_TOLERANCE} step="1" unit="₹" value={values.safeCountTolerance}
              onChange={(e) => change('safeCountTolerance', e.target.value)} />
          </FormField>
          <FormField
            label={t('settings.safeCutoffLabel')}
            help={safeCutoffValid ? t('settings.safeCutoffEffect', { time: values.safeCountCutoff }) : undefined}
            error={safeCutoffValid ? undefined : t('settings.safeCutoffInvalid')}
          >
            <input type="time" step="60" value={values.safeCountCutoff} onChange={(e) => change('safeCountCutoff', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>

      <SetupSaveBar hasChanges={hasChanges && allValid} isSaving={mutation.isPending}
        saveLabel={t('settings.save')} error={error} />
    </form>
  )
}
