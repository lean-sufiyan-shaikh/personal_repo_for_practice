import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { shiftPlanApi, DAY_LABELS } from '../../../api/shiftPlanApi'
import { codeLabel } from '../../../constants/labels'
import type { PreferredDayOff, StaffPreference } from '../../../api/shiftPlanApi'
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
  const { t } = useTranslation(['setup', 'common'])
  const { data: savedPreference, isLoading, isError } = useQuery({
    queryKey: ['staffPref', pumpId, member.id],
    queryFn: () => shiftPlanApi.getPreference(pumpId, member.id),
  })

  return (
    <div className="px-3 py-3 border-t border-border">
      <div className="max-w-sm space-y-3">
        <p className="text-xs font-semibold text-ink">{t('staff.preferences.title', { name: member.fullName })}</p>
        {isLoading ? (
          <div className="flex items-center gap-2 text-xs text-ink-3" role="status">
            <Spinner className="w-3 h-3" />{t('staff.preferences.loading')}
          </div>
        ) : isError ? (
          <p className="ui-error-text" role="alert">{t('staff.preferences.loadFailed')}</p>
        ) : (
          <PreferencesForm
            pumpId={pumpId}
            member={member}
            activeShiftDefs={activeShiftDefs}
            savedPreference={savedPreference}
            onClose={onClose}
          />
        )}
      </div>
    </div>
  )
}

interface FormProps extends Props {
  savedPreference: StaffPreference | undefined
}

function PreferencesForm({ pumpId, member, activeShiftDefs, savedPreference, onClose }: FormProps) {
  const { t } = useTranslation(['setup', 'common'])
  const queryClient = useQueryClient()
  const [prefShiftDefId, setPrefShiftDefId] = useState<number | null>(savedPreference?.preferredShiftDefinitionId ?? null)
  const [prefDayOff, setPrefDayOff] = useState<PreferredDayOff | ''>(savedPreference?.preferredDayOff ?? '')
  const [prefError, setPrefError] = useState<string | null>(null)

  const prefMutation = useMutation({
    mutationFn: () => shiftPlanApi.setPreference(pumpId, member.id, prefShiftDefId, prefDayOff || null),
    onSuccess: () => {
      setPrefError(null)
      queryClient.invalidateQueries({ queryKey: ['staffPref', pumpId, member.id] })
    },
    onError: (err) => setPrefError(parseApiError(err, t('staff.preferences.failed'))),
  })

  return (
    <>
      <div>
        <span className="ui-label">{t('staff.preferences.shift')}</span>
        <SearchableSelect label={t('staff.preferences.shift')}
          value={prefShiftDefId !== null ? String(prefShiftDefId) : ''}
          onChange={v => setPrefShiftDefId(v ? Number(v) : null)}
          placeholder={t('staff.preferences.anyShift')}
          size="sm"
          options={activeShiftDefs.map(d => ({ value: String(d.id), label: `${d.name} · ${d.windowLabel}` }))}
        />
      </div>
      <div>
        <span className="ui-label">{t('staff.preferences.dayOff')}</span>
        <SearchableSelect label={t('staff.preferences.dayOff')}
          value={prefDayOff}
          onChange={v => setPrefDayOff(v as PreferredDayOff | '')}
          placeholder={t('staff.preferences.noPreference')}
          size="sm"
          options={Object.keys(DAY_LABELS).map((day) => ({ value: day, label: codeLabel(day) }))}
        />
      </div>
      {prefError && <p className="ui-error-text" role="alert">{prefError}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => prefMutation.mutate()}
          disabled={prefMutation.isPending}
          className="ui-btn ui-btn-primary min-h-0 px-3 py-1.5 text-xs"
        >
          {prefMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />{t('common:actions.saving')}</span> : t('common:actions.save')}
        </button>
        <button type="button" onClick={onClose}
          className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs">
          {t('common:actions.done')}
        </button>
      </div>
    </>
  )
}
