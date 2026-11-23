import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
    onError: (err) => setError(parseApiError(err, 'Failed to move staff member')),
  })

  return (
    <div className="px-3 py-3 border-t border-border">
      <div className="max-w-sm space-y-3">
        <div>
          <p className="text-xs font-semibold text-ink">Move {member.fullName} to another of your pumps</p>
          <p className="text-xs text-ink-3 mt-0.5">
            They are signed out and see the new pump at their next sign-in. Not allowed while they have an open
            shift or an unresolved discrepancy.
          </p>
        </div>
        {otherPumps.length === 0 ? (
          <p className="ui-help">You have no other active pump to move them to.</p>
        ) : (
          <SearchableSelect label="Move to pump"
            value={targetPumpId}
            onChange={setTargetPumpId}
            placeholder="Select pump"
            options={otherPumps.map(pump => ({ value: String(pump.id), label: pump.name }))}
          />
        )}
        {error && <p role="alert" className="ui-error-text">{error}</p>}
        <div className="flex gap-2">
          <button type="button"
            disabled={!targetPumpId || moveMutation.isPending}
            onClick={() => moveMutation.mutate(Number(targetPumpId))}
            className="ui-btn ui-btn-primary ui-btn-compact">
            {moveMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />Moving…</span> : 'Move'}
          </button>
          <button type="button" onClick={onClose}
            className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs">Cancel</button>
        </div>
      </div>
    </div>
  )
}
