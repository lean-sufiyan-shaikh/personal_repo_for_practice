import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FlaskConical } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { superAdminApi, type OwnerSummary } from '../../api/superAdminApi'
import { ConfirmButton } from '../../components/ConfirmButton'
import { LoadingButton } from '../../components/LoadingButton'
import { parseApiError } from '../../utils/apiError'

/** Creates an owner's demo pump of sample data, or deletes the one they have. */
export function OwnerDemoPumpActions({ owner }: { owner: OwnerSummary }) {
  const { t } = useTranslation('superadmin')
  const queryClient = useQueryClient()
  const hasDemoPump = owner.pumps.some((pump) => pump.demo)
  const refreshOwners = () => queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })

  const createMutation = useMutation({ mutationFn: () => superAdminApi.createDemoPump(owner.ownerId), onSuccess: refreshOwners })
  const deleteMutation = useMutation({ mutationFn: () => superAdminApi.deleteDemoPump(owner.ownerId), onSuccess: refreshOwners })
  const failure = createMutation.error ?? deleteMutation.error

  return (
    <>
      {hasDemoPump ? (
        <ConfirmButton
          onConfirm={() => deleteMutation.mutate()}
          disabled={deleteMutation.isPending}
          triggerLabel={t('owners.deleteDemo')}
          triggerAriaLabel={t('owners.deleteDemo')}
          prompt={t('owners.deleteDemoPrompt')}
          triggerClassName="ui-btn ui-btn-secondary ui-btn-compact"
        />
      ) : (
        <LoadingButton
          type="button"
          onClick={() => createMutation.mutate()}
          isLoading={createMutation.isPending}
          loadingLabel={t('owners.creatingDemo')}
          className="ui-btn-secondary ui-btn-compact inline-flex items-center gap-1"
        >
          <FlaskConical size={12} strokeWidth={2} aria-hidden="true" />{t('owners.createDemo')}
        </LoadingButton>
      )}
      {failure && <p role="alert" className="ui-error-text w-full">{parseApiError(failure, t('owners.demoFailed'))}</p>}
    </>
  )
}
