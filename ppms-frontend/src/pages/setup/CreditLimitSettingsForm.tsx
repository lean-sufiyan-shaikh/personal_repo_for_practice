import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Gauge } from 'lucide-react'
import { creditApi } from '../../api/creditApi'
import type { CreditLimitSettings } from '../../api/creditApi'
import { FormField } from '../../components/FormField'
import { FormGrid } from '../../components/layout/FormGrid'
import { LoadError } from '../../components/LoadError'
import { LoadingButton } from '../../components/LoadingButton'
import { NumericInput } from '../../components/NumericInput'
import { SkeletonRows } from '../../components/Skeleton'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { SetupFormGroup } from './SetupFormGroup'

type LimitSettingsText = Record<keyof CreditLimitSettings, string>

const MIN_NEAR_LIMIT_PERCENT = 50
const MAX_NEAR_LIMIT_PERCENT = 100

/** Loads the pump's credit limit rules, then shows the form with them. */
export function CreditLimitSettingsForm({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('credit')
  const { data: settings, isLoading, isError, refetch } = useQuery({
    queryKey: ['credit-limit-settings', pumpId],
    queryFn: () => creditApi.getCreditLimitSettings(pumpId),
  })
  if (isLoading) return <SkeletonRows count={2} />
  if (isError || !settings) return <LoadError onRetry={() => refetch()} message={t('limitSettings.loadFailed')} />
  return <CreditLimitSettingsFields pumpId={pumpId} saved={{
    defaultCreditLimit: String(settings.defaultCreditLimit),
    managerLimitCap: String(settings.managerLimitCap),
    nearLimitPercent: String(settings.nearLimitPercent),
  }} />
}

function problemOf(field: keyof CreditLimitSettings, text: string): 'amountInvalid' | 'percentInvalid' | null {
  const value = Number(text)
  if (text.trim() === '' || !Number.isFinite(value) || value < 0) return 'amountInvalid'
  if (field === 'nearLimitPercent' && (value < MIN_NEAR_LIMIT_PERCENT || value > MAX_NEAR_LIMIT_PERCENT)) {
    return 'percentInvalid'
  }
  return null
}

/** What new clients get as a limit, how high a manager may set one, and when an account counts as near its limit. */
function CreditLimitSettingsFields({ pumpId, saved }: { pumpId: number; saved: LimitSettingsText }) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [values, setValues] = useState(saved)
  const [showProblems, setShowProblems] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)
  const fields = Object.keys(values) as (keyof CreditLimitSettings)[]
  const hasProblems = fields.some(field => problemOf(field, values[field]) !== null)
  const errorOf = (field: keyof CreditLimitSettings) => {
    const problem = showProblems ? problemOf(field, values[field]) : null
    return problem ? t(`limitSettings.${problem}`, { min: MIN_NEAR_LIMIT_PERCENT, max: MAX_NEAR_LIMIT_PERCENT }) : undefined
  }

  const mutation = useMutation({
    mutationFn: () => creditApi.updateCreditLimitSettings(pumpId, {
      defaultCreditLimit: Number(values.defaultCreditLimit),
      managerLimitCap: Number(values.managerLimitCap),
      nearLimitPercent: Number(values.nearLimitPercent),
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-limit-settings', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      addToast(t('limitSettings.saved'), 'success')
    },
    onError: (err) => setServerError(parseApiError(err, t('limitSettings.saveFailed'))),
  })

  const change = (field: keyof CreditLimitSettings, value: string) => {
    setValues(current => ({ ...current, [field]: value }))
    setServerError(null)
  }

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    setShowProblems(true)
    if (hasProblems) return
    mutation.mutate()
  }

  return (
    <form onSubmit={handleSave} className="space-y-4">
      <SetupFormGroup icon={Gauge} title={t('limitSettings.title')} hint={t('limitSettings.hint')}>
        <FormGrid>
          <FormField label={t('limitSettings.defaultLimit')} help={t('limitSettings.defaultLimitHelp')} error={errorOf('defaultCreditLimit')}>
            <NumericInput unit="₹" min="0" step="0.01" value={values.defaultCreditLimit}
              onChange={e => change('defaultCreditLimit', e.target.value)} />
          </FormField>
          <FormField label={t('limitSettings.managerCap')} help={t('limitSettings.managerCapHelp')} error={errorOf('managerLimitCap')}>
            <NumericInput unit="₹" min="0" step="0.01" value={values.managerLimitCap}
              onChange={e => change('managerLimitCap', e.target.value)} />
          </FormField>
          <FormField label={t('limitSettings.nearLimitPercent')} help={t('limitSettings.nearLimitPercentHelp')} error={errorOf('nearLimitPercent')}>
            <NumericInput unit="%" step="1" value={values.nearLimitPercent}
              onChange={e => change('nearLimitPercent', e.target.value)} />
          </FormField>
        </FormGrid>
      </SetupFormGroup>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
      <div className="flex justify-end">
        <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')} className="ui-btn-primary">
          {t('limitSettings.save')}
        </LoadingButton>
      </div>
    </form>
  )
}
