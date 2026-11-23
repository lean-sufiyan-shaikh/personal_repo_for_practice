import { AlertTriangle, Check, Fuel, Users, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { PumpSummary, UpdatePumpRequest } from '../../api/superAdminApi'
import { StatusBadge } from '../../components/StatusBadge'
import { EditPumpForm } from './EditPumpForm'

interface PumpCardProps {
  pump: PumpSummary
  isEditOpen: boolean
  onToggleEdit: () => void
  onToggleEnabled: () => void
  isSaving: boolean
  editError: string | null
  onSaveEdit: (data: UpdatePumpRequest) => void
}

export function PumpCard({ pump, isEditOpen, onToggleEdit, onToggleEnabled, isSaving, editError, onSaveEdit }: PumpCardProps) {
  const { t } = useTranslation(['superadmin', 'common'])
  return (
    <div className={`rounded-control border p-2.5 ${
      pump.enabled ? 'border-border bg-bg' : 'border-bad bg-bad-bg opacity-60'
    }`}>
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-start gap-2 text-xs min-w-0">
          <Fuel size={12} strokeWidth={1.8} className="text-ink-3 mt-0.5 flex-shrink-0" />
          <div className="min-w-0">
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className={`font-semibold ${!pump.enabled ? 'line-through text-ink-3' : 'text-ink'}`}>
                {pump.pumpName}
              </span>
              {!pump.enabled && (
                <StatusBadge tone="bad" label={t('pump.disabled')} />
              )}
            </div>
            <p className="text-ink-3 mt-0.5">{pump.pumpAddress}</p>
            {/* Staff count badge */}
            <div className="mt-1 flex flex-wrap gap-1.5">
              <StatusBadge tone="neutral" icon={Users} label={t('pump.staff', { value: pump.staffCount })} />
              <StatusBadge
                tone={pump.setupDoneCount === pump.setupRequiredCount ? 'ok' : 'warn'}
                icon={pump.setupDoneCount === pump.setupRequiredCount ? Check : AlertTriangle}
                label={t('pump.setup', { done: pump.setupDoneCount, required: pump.setupRequiredCount })}
              />
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1 flex-shrink-0">
          <button
            onClick={onToggleEdit}
            className={`ui-btn min-h-0 px-3 py-1 text-xs border transition-colors ${
              isEditOpen
                ? 'border-primary text-info bg-info-bg'
                : 'border-border text-ink-3 hover:border-info hover:text-primary hover:bg-info-bg'
            }`}
          >
            {isEditOpen ? <X size={12} strokeWidth={2} /> : t('common:actions.edit')}
          </button>
          <button
            onClick={onToggleEnabled}
            disabled={isSaving}
            className={`ui-btn min-h-0 px-3 py-1 text-xs border transition-colors disabled:opacity-40 ${
              pump.enabled
                ? 'border-bad text-bad hover:bg-bad-bg'
                : 'border-ok text-ok hover:bg-ok-bg'
            }`}
          >
            {pump.enabled ? t('common:actions.disable') : t('common:actions.enable')}
          </button>
        </div>
      </div>

      {/* Inline edit form */}
      {isEditOpen && (
        <EditPumpForm pump={pump} isSaving={isSaving} error={editError} onSave={onSaveEdit} />
      )}
    </div>
  )
}
