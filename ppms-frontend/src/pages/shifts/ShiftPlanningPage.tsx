import { useTranslation } from 'react-i18next'
import { Reveal } from '../../components/Reveal'
import { useShiftPlanning } from './planning/useShiftPlanning'
import { WeekNavigator } from './planning/WeekNavigator'
import { GeneratePlanModal } from './planning/GeneratePlanModal'
import { PlanningGrid } from './planning/PlanningGrid'

// ── Main page ──────────────────────────────────────────────────────────────────

export default function ShiftPlanningPage() {
  const { t } = useTranslation('shifts')
  const form = useShiftPlanning()
  const { isOwnerOrAdmin, pumpId } = form

  if (!isOwnerOrAdmin && !pumpId) {
    return (
      <div className="ui-page ui-page--narrow">
        <div className="ui-alert ui-alert-warning text-sm">
          {t('page.notAssigned')}
        </div>
      </div>
    )
  }

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* Page title */}
      <Reveal delay={60}>
      <div>
        <h1 className="ui-title-sm">{t('planning.title')}</h1>
        <p className="ui-subtitle">
          {isOwnerOrAdmin
            ? t('planning.subtitleOwner')
            : t('planning.subtitleStaff')}
        </p>
      </div>
      </Reveal>

      {pumpId && (
        <>
          {/* Week navigator + actions */}
          <WeekNavigator form={form} />


          {/* Auto-generate modal */}
          <GeneratePlanModal form={form} />


          {/* Planning grid */}
          <PlanningGrid form={form} />


          {/* Legend */}
          <Reveal delay={270}>
          <div className="flex flex-wrap gap-4 text-xs text-ink-3">
            <span className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-ink-3 inline-block" /> {t('planning.legend.planned')}
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-ok inline-block" /> {t('planning.legend.confirmed')}
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-bad inline-block" /> {t('planning.legend.absent')}
            </span>
          </div>
          </Reveal>
        </>
      )}
    </div>
  )
}
