import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { parseApiError } from '../../../utils/apiError'
import { shiftPlanApi, DAY_LABELS } from '../../../api/shiftPlanApi'
import type { PreferredDayOff } from '../../../api/shiftPlanApi'
import type { StaffMember } from '../../../api/userApi'
import type { ShiftDefinition } from '../../../api/shiftDefinitionApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { Spinner } from '../../../components/Spinner'

interface Props {
  pumpId: number
  member: StaffMember
  activeShiftDefs: ShiftDefinition[]
  onClose: () => void
}

export function PreferencesPanel({ pumpId, member, activeShiftDefs, onClose }: Props) {
  const queryClient = useQueryClient()
  const [prefShiftDefId, setPrefShiftDefId] = useState<number | null>(null)
  const [prefDayOff, setPrefDayOff] = useState<PreferredDayOff | ''>('')
  const [prefError, setPrefError] = useState<string | null>(null)

  const prefMutation = useMutation({
    mutationFn: ({ userId }: { userId: number }) =>
      shiftPlanApi.setPreference(pumpId, userId, prefShiftDefId, prefDayOff || null),
    onSuccess: () => {
      setPrefError(null)
      queryClient.invalidateQueries({ queryKey: ['staffPref', pumpId, member.id] })
    },
    onError: (err) => setPrefError(parseApiError(err, 'Failed to save preferences')),
  })

  return (
    <div className="px-3 py-3 border-t border-slate-100">
      <div className="max-w-sm space-y-3">
        <p className="text-xs font-semibold text-slate-700">Shift preferences for {member.fullName}</p>
        <div>
          <span className="ui-label">Preferred shift</span>
          <SearchableSelect label="Preferred shift"
            value={prefShiftDefId !== null ? String(prefShiftDefId) : ''}
            onChange={v => setPrefShiftDefId(v ? Number(v) : null)}
            placeholder="Any shift"
            size="sm"
            options={activeShiftDefs.map(d => ({ value: String(d.id), label: `${d.name} · ${d.windowLabel}` }))}
          />
        </div>
        <div>
          <span className="ui-label">Preferred day off</span>
          <SearchableSelect label="Preferred day off"
            value={prefDayOff}
            onChange={v => setPrefDayOff(v as PreferredDayOff | '')}
            placeholder="No preference"
            size="sm"
            options={Object.entries(DAY_LABELS).map(([val, label]) => ({ value: val, label }))}
          />
        </div>
        {prefError && <p className="ui-error-text">{prefError}</p>}
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => prefMutation.mutate({ userId: member.id })}
            disabled={prefMutation.isPending}
            className="ui-btn ui-btn-primary min-h-0 px-3 py-1.5 text-xs"
          >
            {prefMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />Saving…</span> : 'Save'}
          </button>
          <button type="button" onClick={onClose}
            className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs">
            Done
          </button>
        </div>
      </div>
    </div>
  )
}
