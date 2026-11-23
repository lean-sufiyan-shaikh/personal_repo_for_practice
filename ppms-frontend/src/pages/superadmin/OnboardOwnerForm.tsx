import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { superAdminApi } from '../../api/superAdminApi'
import type { OnboardOwnerResponse } from '../../api/superAdminApi'
import { PasswordInput } from '../../components/PasswordInput'
import { FormField } from '../../components/FormField'
import { parseApiError } from '../../utils/apiError'
import { onboardSchema } from './superAdminSchemas'
import type { OnboardFormData, OnboardFormInput } from './superAdminSchemas'

interface OnboardOwnerFormProps {
  onSubmitStart: () => void
  onCreated: (owner: OnboardOwnerResponse, password: string) => void
}

export function OnboardOwnerForm({ onSubmitStart, onCreated }: OnboardOwnerFormProps) {
  const { t } = useTranslation('superadmin')
  const queryClient = useQueryClient()
  const [serverError, setServerError] = useState<string | null>(null)

  const schema = useMemo(() => onboardSchema(t), [t])
  const { register, handleSubmit, reset, formState: { errors } } = useForm<OnboardFormInput, unknown, OnboardFormData>({
    resolver: zodResolver(schema),
  })

  const onboardMutation = useMutation({
    mutationFn: superAdminApi.onboardOwner,
    onSuccess: (data, variables) => {
      onCreated(data, variables.password)
      setServerError(null)
      reset()
      queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })
    },
    onError: (err) => {
      setServerError(parseApiError(err, t('onboard.failed')))
    },
  })

  const onSubmit = (data: OnboardFormData) => {
    setServerError(null)
    onSubmitStart()
    onboardMutation.mutate(data)
  }

  return (
    <div className="lg:col-span-2">
      <div className="ui-card p-0">
        <div className="px-5 py-4 border-b border-border">
          <h2 className="text-sm font-semibold text-ink">{t('onboard.title')}</h2>
          <p className="text-xs text-ink-3 mt-0.5">{t('onboard.subtitle')}</p>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="px-5 py-4 space-y-4">

          <fieldset>
            <legend className="text-xs font-semibold text-ink-3 uppercase tracking-wide mb-3">
              {t('onboard.ownerDetails')}
            </legend>
            <div className="space-y-3">
              <FormField label={t('onboard.fullName')} error={errors.fullName?.message}>
                <input
                  {...register('fullName')}
                  type="text"
                  placeholder={t('onboard.fullNamePlaceholder')}
                  className="text-sm"
                />
              </FormField>

              <FormField label={t('onboard.phoneNumber')} error={errors.phoneNumber?.message}>
                <input
                  {...register('phoneNumber')}
                  type="tel"
                  inputMode="numeric"
                  maxLength={10}
                  placeholder={t('onboard.phonePlaceholder')}
                  className="text-sm"
                />
              </FormField>

              <FormField label={t('onboard.initialPassword')} error={errors.password?.message}>
                <PasswordInput
                  {...register('password')}
                  placeholder={t('onboard.passwordPlaceholder')}
                  className="text-sm"
                />
              </FormField>
            </div>
          </fieldset>

          <fieldset>
            <legend className="text-xs font-semibold text-ink-3 uppercase tracking-wide mb-3">
              {t('onboard.pumpDetails')}
            </legend>
            <div className="space-y-3">
              <FormField label={t('fields.pumpName')} error={errors.pumpName?.message}>
                <input
                  {...register('pumpName')}
                  type="text"
                  placeholder={t('onboard.pumpNamePlaceholder')}
                  className="text-sm"
                />
              </FormField>

              <FormField label={t('fields.pumpAddress')} error={errors.pumpAddress?.message}>
                <input
                  {...register('pumpAddress')}
                  type="text"
                  placeholder={t('fields.addressPlaceholder')}
                  className="text-sm"
                />
              </FormField>

              <FormField label={t('fields.maxDuCount')} error={errors.maxDuCount?.message}>
                <input
                  {...register('maxDuCount')}
                  type="number"
                  min={1}
                  max={20}
                  placeholder={t('fields.maxDuPlaceholder')}
                  className="text-sm"
                />
              </FormField>
            </div>
          </fieldset>

          {serverError && (
            <div role="alert" className="ui-alert ui-alert-danger">
              <p className="text-bad text-sm">{serverError}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={onboardMutation.isPending}
            className="ui-btn ui-btn-primary w-full"
          >
            {onboardMutation.isPending ? t('onboard.creating') : t('onboard.submit')}
          </button>
        </form>
      </div>
    </div>
  )
}
