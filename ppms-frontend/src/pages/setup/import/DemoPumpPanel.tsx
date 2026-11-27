import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FlaskConical } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { demoPumpApi } from '../../../api/demoPumpApi'
import { pumpApi } from '../../../api/pumpApi'
import { ConfirmButton } from '../../../components/ConfirmButton'
import { LoadingButton } from '../../../components/LoadingButton'
import { parseApiError } from '../../../utils/apiError'

/** Lets an owner create one demo pump of sample data to explore, and delete it again with all its data. */
export function DemoPumpPanel() {
  const { t } = useTranslation('setup')
  const queryClient = useQueryClient()
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { data: pumps = [] } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps })
  const demoPump = pumps.find((pump) => pump.demo)

  const refreshPumps = () => {
    queryClient.invalidateQueries({ queryKey: ['myPumps'] })
    queryClient.invalidateQueries({ queryKey: ['pumpsOverview'] })
  }

  const createMutation = useMutation({
    mutationFn: demoPumpApi.create,
    onMutate: () => { setNotice(null); setError(null) },
    onSuccess: () => { setNotice(t('demo.created')); refreshPumps() },
    onError: (err) => setError(parseApiError(err)),
  })
  const deleteMutation = useMutation({
    mutationFn: demoPumpApi.delete,
    onMutate: () => { setNotice(null); setError(null) },
    onSuccess: () => { setNotice(t('demo.deleted')); refreshPumps() },
    onError: (err) => setError(parseApiError(err)),
  })

  return (
    <div className="space-y-2 rounded-control border border-border bg-bg p-3">
      <p className="flex items-center gap-2 text-sm font-semibold text-ink">
        <FlaskConical size={16} strokeWidth={2} aria-hidden="true" className="text-info" />
        {t('demo.title')}
      </p>
      <p className="text-sm text-ink-2">{t('demo.body')}</p>
      {demoPump ? (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm text-ink">{t('demo.existing', { name: demoPump.name })}</span>
          <ConfirmButton
            onConfirm={() => deleteMutation.mutate()}
            disabled={deleteMutation.isPending}
            triggerLabel={t('demo.delete')}
            triggerAriaLabel={t('demo.delete')}
            prompt={t('demo.deletePrompt')}
            triggerClassName="ui-btn ui-btn-secondary ui-btn-compact"
          />
        </div>
      ) : (
        <LoadingButton
          type="button"
          onClick={() => createMutation.mutate()}
          isLoading={createMutation.isPending}
          loadingLabel={t('demo.creating')}
          className="ui-btn-secondary"
        >
          {t('demo.create')}
        </LoadingButton>
      )}
      {createMutation.isPending && <p role="status" className="ui-help">{t('demo.creating')}</p>}
      {notice && <p role="status" className="text-sm text-ok">{notice}</p>}
      {error && <p role="alert" className="ui-error-text">{error}</p>}
    </div>
  )
}
