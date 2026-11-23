import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { userApi } from '../../../api/userApi'
import type { StaffMember } from '../../../api/userApi'
import { pumpApi } from '../../../api/pumpApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { Spinner } from '../../../components/Spinner'

interface Props {
  pumpId: number
  member: StaffMember
  onClose: () => void
}

export function MoveToPumpPanel({ pumpId, member, onClose }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  const queryClient = useQueryClient()
  const [targetPumpId, setTargetPumpId] = useState('')
  const [error, setError] = useState<string | null>(null)

  const { data: pumps = [] } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps, staleTime: 5 * 60_000 })
  const otherPumps = pumps.filter(pump => pump.id !== pumpId)

  const moveMutation = useMutation({
    mutationFn: (toPumpId: number) => userApi.moveToPump(member.id, toPumpId),
    onSuccess: (_moved, toPumpId) => {
      setError(null)
      onClose()
      queryClient.invalidateQueries({ queryKey: ['staff', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['staff', toPumpId] })
      queryClient.invalidateQueries({ queryKey: ['operators', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['operators', toPumpId] })
    },
    onError: (err) => setError(parseApiError(err, t('staff.move.failed'))),
  })

  return (
    <div className="px-3 py-3 border-t border-border">
      <div className="max-w-sm space-y-3">
        <div>
          <p className="text-xs font-semibold text-ink">{t('staff.move.title', { name: member.fullName })}</p>
          <p className="text-xs text-ink-3 mt-0.5">
            {t('staff.move.explanation')}
          </p>
        </div>
        {otherPumps.length === 0 ? (
          <p className="ui-help">{t('staff.move.noOtherPump')}</p>
        ) : (
          <SearchableSelect label={t('staff.move.selectLabel')}
            value={targetPumpId}
            onChange={setTargetPumpId}
            placeholder={t('staff.move.selectPlaceholder')}
            options={otherPumps.map(pump => ({ value: String(pump.id), label: pump.name }))}
          />
        )}
        {error && <p role="alert" className="ui-error-text">{error}</p>}
        <div className="flex gap-2">
          <button type="button"
            disabled={!targetPumpId || moveMutation.isPending}
            onClick={() => moveMutation.mutate(Number(targetPumpId))}
            className="ui-btn ui-btn-primary ui-btn-compact">
            {moveMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />{t('staff.move.moving')}</span> : t('staff.move.submit')}
          </button>
          <button type="button" onClick={onClose}
            className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs">{t('common:actions.cancel')}</button>
        </div>
      </div>
    </div>
  )
}
