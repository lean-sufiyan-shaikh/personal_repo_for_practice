import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { superAdminApi } from '../../api/superAdminApi'
import type { AddPumpRequest, OwnerSummary } from '../../api/superAdminApi'
import { FormField } from '../../components/FormField'
import { parseApiError } from '../../utils/apiError'
import { addPumpSchema } from './superAdminSchemas'
import type { AddPumpFormData, AddPumpFormInput } from './superAdminSchemas'

interface AddPumpFormProps {
  owner: OwnerSummary
  onDone: () => void
}

export function AddPumpForm({ owner, onDone }: AddPumpFormProps) {
  const { t } = useTranslation('superadmin')
  const queryClient = useQueryClient()
  const [addPumpError, setAddPumpError] = useState<string | null>(null)
  const schema = useMemo(() => addPumpSchema(t), [t])

  const {
    register: registerPump,
    handleSubmit: handleSubmitPump,
    formState: { errors: pumpErrors },
  } = useForm<AddPumpFormInput, unknown, AddPumpFormData>({
    resolver: zodResolver(schema),
  })

  const addPumpMutation = useMutation({
    mutationFn: ({ ownerId, data }: { ownerId: number; data: AddPumpRequest }) =>
      superAdminApi.addPumpToOwner(ownerId, data),
    onSuccess: () => {
      onDone()
      queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })
      queryClient.invalidateQueries({ queryKey: ['superadmin-analytics'] })
    },
    onError: (err) =>
      setAddPumpError(parseApiError(err, t('addPump.failed'))),
  })

  const onAddPumpSubmit = (data: AddPumpFormData) => {
    setAddPumpError(null)
    addPumpMutation.mutate({ ownerId: owner.ownerId, data })
  }

  return (
    <div className="ui-card-plain border-info bg-info-bg/50 p-4">
      <form onSubmit={handleSubmitPump(onAddPumpSubmit)} className="ui-inline-form space-y-3 max-w-md">
        <div>
          <p className="ui-inline-form__title">{t('addPump.title', { name: owner.fullName })}</p>
          <p className="ui-inline-form__copy">{t('addPump.copy')}</p>
        </div>
        <FormField label={t('fields.pumpName')} error={pumpErrors.pumpName?.message}>
          <input
            {...registerPump('pumpName')}
            type="text"
            placeholder={t('addPump.pumpNamePlaceholder')}
            className="text-sm"
          />
        </FormField>
        <FormField label={t('fields.pumpAddress')} error={pumpErrors.pumpAddress?.message}>
          <input
            {...registerPump('pumpAddress')}
            type="text"
            placeholder={t('fields.addressPlaceholder')}
            className="text-sm"
          />
        </FormField>
        <FormField label={t('fields.maxDuCount')} error={pumpErrors.maxDuCount?.message}>
          <input
            {...registerPump('maxDuCount')}
            type="number"
            min={1}
            max={20}
            placeholder={t('fields.maxDuPlaceholder')}
            className="text-sm"
          />
        </FormField>
        {addPumpError && (
          <p role="alert" className="ui-error-text">{addPumpError}</p>
        )}
        <button
          type="submit"
          disabled={addPumpMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {addPumpMutation.isPending ? t('addPump.adding') : t('addPump.submit')}
        </button>
      </form>
    </div>
  )
}
