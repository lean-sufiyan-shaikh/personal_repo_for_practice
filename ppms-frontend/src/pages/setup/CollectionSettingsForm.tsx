import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { BellRing, QrCode, ShieldCheck } from 'lucide-react'
import { collectionApi } from '../../api/collectionApi'
import { FormField } from '../../components/FormField'
import { FormGrid } from '../../components/layout/FormGrid'
import { LoadError } from '../../components/LoadError'
import { LoadingButton } from '../../components/LoadingButton'
import { NumericInput } from '../../components/NumericInput'
import { SkeletonRows } from '../../components/Skeleton'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { SetupFormGroup } from './SetupFormGroup'
import { problemsOf, settingsOf, textOf } from './collectionSettings'
import type { CollectionSettingsField, CollectionSettingsText } from './collectionSettings'

/** Loads the pump's collection settings, then shows the form with them. */
export function CollectionSettingsForm({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('collections')
  const { data: settings, isLoading, isError, refetch } = useQuery({
    queryKey: ['collections', pumpId, 'settings'],
    queryFn: () => collectionApi.getSettings(pumpId),
  })
  if (isLoading) return <SkeletonRows count={3} />
  if (isError || !settings) return <LoadError onRetry={() => refetch()} message={t('settings.saveFailed')} />
  return <CollectionSettingsFields pumpId={pumpId} saved={textOf(settings)} />
}

/** Reminder stages, the credit hold, the UPI details used for pay links and QR codes, and credit payment approval. */
function CollectionSettingsFields({ pumpId, saved }: { pumpId: number; saved: CollectionSettingsText }) {
  const { t } = useTranslation(['collections', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [values, setValues] = useState(saved)
  const [showProblems, setShowProblems] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)
  const problems = problemsOf(values)
  const errorOf = (field: CollectionSettingsField) => (showProblems && problems[field] ? t(problems[field]) : undefined)

  const mutation = useMutation({
    mutationFn: () => collectionApi.updateSettings(pumpId, settingsOf(values)),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['collections', pumpId] })
      addToast(t('settings.saved'), 'success')
    },
    onError: (err) => setServerError(parseApiError(err, t('settings.saveFailed'))),
  })

  const change = (field: CollectionSettingsField, value: string) => {
    setValues((current) => ({ ...current, [field]: value }))
    setServerError(null)
  }

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    setShowProblems(true)
    if (Object.keys(problems).length > 0) return
    mutation.mutate()
  }

  return (
    <form onSubmit={handleSave} className="space-y-4">
      <SetupFormGroup icon={BellRing} title={t('settings.title')} hint={t('settings.hint')}>
        <FormGrid>
          <FormField label={t('settings.stages')} help={t('settings.stagesHelp')} error={errorOf('stages')}>
            <input inputMode="numeric" value={values.stages} onChange={(e) => change('stages', e.target.value)} />
          </FormField>
          <FormField label={t('settings.holdDays')} help={t('settings.holdDaysHelp')} error={errorOf('holdDays')}>
            <NumericInput min="1" max="365" step="1" inputMode="numeric" value={values.holdDays}
              onChange={(e) => change('holdDays', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>
      <SetupFormGroup icon={QrCode} title={t('settings.upiTitle')} hint={t('settings.upiHint')}>
        <FormGrid>
          <FormField label={t('settings.upiId')} help={t('settings.upiIdHelp')} error={errorOf('upiId')}>
            <input value={values.upiId} autoCapitalize="none" spellCheck={false} onChange={(e) => change('upiId', e.target.value)} />
          </FormField>
          <FormField label={t('settings.payeeName')} error={errorOf('payeeName')}>
            <input value={values.payeeName} maxLength={100} onChange={(e) => change('payeeName', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>
      <SetupFormGroup icon={ShieldCheck} title={t('settings.paymentsTitle')} hint={t('settings.paymentsHint')}>
        <FormGrid>
          <FormField label={t('settings.paymentThreshold')} help={t('settings.paymentThresholdHelp')} error={errorOf('paymentThreshold')}>
            <NumericInput unit="₹" min="0.01" step="0.01" value={values.paymentThreshold}
              onChange={(e) => change('paymentThreshold', e.target.value)} />
          </FormField>
          <FormField label={t('settings.backdatedDays')} help={t('settings.backdatedDaysHelp')} error={errorOf('backdatedDays')}>
            <NumericInput min="0" max="365" step="1" inputMode="numeric" value={values.backdatedDays}
              onChange={(e) => change('backdatedDays', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
      <div className="flex justify-end">
        <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')} className="ui-btn-primary">
          {t('settings.save')}
        </LoadingButton>
      </div>
    </form>
  )
}
