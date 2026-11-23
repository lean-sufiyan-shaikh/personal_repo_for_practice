import { useState } from 'react'
import { withToggled } from '../../../utils/setToggle'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ChevronDown, ChevronRight, Moon } from 'lucide-react'
import { parseApiError } from '../../../utils/apiError'
import { shiftDefinitionApi } from '../../../api/shiftDefinitionApi'
import { localDateInputValue } from '../../../utils/date'
import { IconButton } from '../../../components/IconButton'
import { StatusBadge } from '../../../components/StatusBadge'
import { onActivateKey } from '../../../utils/keyboard'
import { CreateShiftBatchForm } from './CreateShiftBatchForm'

export function ShiftDefinitionsContent({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation(['setup', 'common'])
  const qc = useQueryClient()

  const { data: allDefs = [], isLoading } = useQuery({
    queryKey: ['shift-definitions', pumpId],
    queryFn: () => shiftDefinitionApi.getAll(pumpId),
  })

  // Group by (effectiveFrom, effectiveTo) composite key — two groups can share the same
  // effectiveFrom when a disabled group and a new active group coexist on the same date.
  const groups = allDefs.reduce<Record<string, typeof allDefs>>((acc, d) => {
    const key = `${d.effectiveFrom}|${d.effectiveTo ?? 'open'}`
    if (!acc[key]) acc[key] = []
    acc[key].push(d)
    return acc
  }, {})
  // Sort newest effectiveFrom first; within same date, active (open-ended) groups come before disabled.
  const sortedGroupKeys = Object.keys(groups).sort((a, b) => {
    const [aFrom, aTo] = a.split('|')
    const [bFrom, bTo] = b.split('|')
    if (bFrom !== aFrom) return bFrom.localeCompare(aFrom)
    // Same effectiveFrom: active ('open') sorts before disabled (a date string)
    if (aTo === 'open') return -1
    if (bTo === 'open') return 1
    return bTo.localeCompare(aTo)
  })

  const [showForm, setShowForm] = useState(false)

  const [actionError, setActionError] = useState<string | null>(null)

  // ── Collapsible groups — disabled groups start collapsed ────────────────────
  const [collapsedDates, setCollapsedDates] = useState<Set<string>>(new Set())
  const [collapsedInitialized, setCollapsedInitialized] = useState(false)
  if (!collapsedInitialized && Object.keys(groups).length > 0) {
    const disabled = new Set<string>()
    Object.entries(groups).forEach(([groupKey, defs]) => {
      // Collapse groups where every definition is disabled (effectiveTo is set).
      if (defs.every(d => d.effectiveTo !== null)) disabled.add(groupKey)
    })
    setCollapsedDates(disabled)
    setCollapsedInitialized(true)
  }
  const toggleCollapsed = (date: string) =>
    setCollapsedDates(prev => withToggled(prev, date))

  // ── Delete ──────────────────────────────────────────────────────────────────
  // Stores the composite group key "effectiveFrom|effectiveTo" of the group pending deletion.
  const [confirmDeleteKey, setConfirmDeleteKey] = useState<string | null>(null)

  const deleteMutation = useMutation({
    mutationFn: (groupKey: string) => {
      const [effectiveFrom, effectiveToRaw] = groupKey.split('|')
      const effectiveTo = effectiveToRaw === 'open' ? null : effectiveToRaw
      return shiftDefinitionApi.deleteGroup(pumpId, effectiveFrom, effectiveTo)
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shift-definitions', pumpId] })
      qc.invalidateQueries({ queryKey: ['shift-definitions-active', pumpId] })
      setConfirmDeleteKey(null)
      setActionError(null)
    },
    onError: (err) => {
      setConfirmDeleteKey(null)
      setActionError(parseApiError(err, t('shiftDefs.deleteFailed')))
    },
  })

  // ── Disable ─────────────────────────────────────────────────────────────────
  const [disablingDate, setDisablingDate] = useState<string | null>(null) // effectiveFrom of group being disabled
  const [disableFromToday, setDisableFromToday] = useState(true)
  const [disablePickedDate, setDisablePickedDate] = useState('')

  const todayStr = localDateInputValue()

  const disableMutation = useMutation({
    mutationFn: ({ effectiveFrom, disableDate }: { effectiveFrom: string; disableDate: string }) =>
      shiftDefinitionApi.disableGroup(pumpId, effectiveFrom, disableDate),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shift-definitions', pumpId] })
      qc.invalidateQueries({ queryKey: ['shift-definitions-active', pumpId] })
      qc.refetchQueries({ queryKey: ['shift-definitions', pumpId] })
      setDisablingDate(null)
      setActionError(null)
    },
    onError: (err) => {
      setDisablingDate(null)
      setActionError(parseApiError(err, t('shiftDefs.disableFailed')))
    },
  })

  const openDisable = (date: string) => {
    setActionError(null)
    setDisableFromToday(true)
    setDisablePickedDate(date) // reuse as default min, will be overridden if user picks
    setDisablingDate(date)
  }

  const confirmDisable = () => {
    if (!disablingDate) return
    const disableDate = disableFromToday ? todayStr : disablePickedDate
    if (!disableDate) { setActionError(t('shiftDefs.selectDisableDate')); return }
    disableMutation.mutate({ effectiveFrom: disablingDate, disableDate })
  }

  if (isLoading) return <p className="text-xs text-ink-3 py-2">{t('tankers.loading')}</p>

  return (
    <div className="space-y-4">

      {/* Existing groups */}
      {sortedGroupKeys.length === 0 && !showForm && (
        <p className="text-xs text-ink-3">{t('shiftDefs.empty')}</p>
      )}

      {sortedGroupKeys.map(groupKey => {
        const defs = groups[groupKey].slice().sort((a, b) => a.sortOrder - b.sortOrder)
        const effectiveFromDate = defs[0].effectiveFrom
        const isActive = defs.some(d => d.effectiveTo === null)
        const isCollapsed = collapsedDates.has(groupKey)
        return (
          <div key={groupKey} className="ui-card p-0 overflow-hidden">
            <div
              className="ui-accordion-trigger select-none"
              >
                <div
                  className="flex flex-1 items-center gap-2 cursor-pointer"
                  role="button"
                  tabIndex={0}
                  aria-expanded={!isCollapsed}
                  onClick={() => toggleCollapsed(groupKey)}
                  onKeyDown={onActivateKey(() => toggleCollapsed(groupKey))}
                >
                {isCollapsed
                  ? <ChevronRight size={14} strokeWidth={2} className="text-ink-3" />
                  : <ChevronDown size={14} strokeWidth={2} className="text-ink-3" />
                }
                <span className="text-xs font-semibold text-ink">
                  {effectiveFromDate}
                  {defs[0]?.effectiveTo ? ` – ${defs[0].effectiveTo}` : t('shiftDefs.onwards')}
                </span>
                {isActive
                  ? <StatusBadge tone="ok" label={t('shiftDefs.active')} />
                  : <StatusBadge tone="neutral" label={t('tanks.disabled')} />
                }
              </div>
              <div className="flex items-center gap-3">
                {/* Disable button — only for open-ended (active) groups */}
                {isActive && (
                  confirmDeleteKey === groupKey ? null : (
                    <button
                      onClick={() => openDisable(effectiveFromDate)}
                      className="text-xs text-warn hover:text-warn-strong font-medium"
                    >
                      {t('actions.disable')}
                    </button>
                  )
                )}
                {/* Delete button with confirm — available for all groups (active or disabled) */}
                {confirmDeleteKey === groupKey ? (
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-bad">{t('shiftDefs.deletePrompt')}</span>
                    <button
                      onClick={() => deleteMutation.mutate(groupKey)}
                      disabled={deleteMutation.isPending}
                      className="ui-btn ui-btn-danger min-h-0 px-2 py-0.5 text-xs disabled:opacity-40"
                    >
                      {deleteMutation.isPending ? t('shiftDefs.deleting') : t('shiftDefs.confirmDelete')}
                    </button>
                    <button
                      onClick={() => setConfirmDeleteKey(null)}
                      className="ui-btn ui-btn-secondary min-h-0 px-2 py-0.5 text-xs"
                    >
                      {t('common:actions.cancel')}
                    </button>
                  </div>
                ) : (
                  <button
                    onClick={() => { setActionError(null); setConfirmDeleteKey(groupKey) }}
                    className="text-xs text-bad hover:text-bad-strong font-medium"
                  >
                    {t('shiftDefs.deleteGroup')}
                  </button>
                )}
              </div>
            </div>
            {/* Collapsible body — disable form + shift rows */}
            {!isCollapsed && (<>
            {/* Disable inline form */}
            {disablingDate === effectiveFromDate && (
              <div className="px-4 py-3 bg-warn-bg border-t border-warn space-y-3">
                <p className="text-xs font-medium text-warn-strong">
                  {t('shiftDefs.disableExplanation')}
                </p>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={disableFromToday}
                    onChange={e => setDisableFromToday(e.target.checked)}
                    className="w-3.5 h-3.5 accent-warn"
                  />
                  <span className="text-xs text-ink">{t('shiftDefs.disableFromToday', { date: todayStr })}</span>
                </label>
                {!disableFromToday && (
                  <div>
                    <label className="block">
                      <span className="ui-label">{t('shiftDefs.lastActiveDate')}</span>
                      <input
                        type="date"
                        value={disablePickedDate}
                        min={todayStr}
                        onChange={e => setDisablePickedDate(e.target.value)}
                        className="text-sm"
                      />
                    </label>
                    <p className="text-xs text-ink-3 mt-1">{t('shiftDefs.activeUntilHint')}</p>
                  </div>
                )}
                <div className="flex gap-2">
                  <button
                    onClick={confirmDisable}
                    disabled={disableMutation.isPending}
                    className="ui-btn ui-btn-warning min-h-0 px-3 py-1.5 text-xs disabled:opacity-40"
                  >
                    {disableMutation.isPending ? t('nozzles.disable.disabling') : t('shiftDefs.confirmDisable')}
                  </button>
                  <button
                    onClick={() => setDisablingDate(null)}
                    className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs"
                  >
                    {t('common:actions.cancel')}
                  </button>
                </div>
              </div>
            )}

            <div className="divide-y divide-border">
              {defs.map(d => (
                <div key={d.id} className="px-4 py-2.5 flex items-center gap-3">
                  <span className="text-xs font-medium text-ink-2 w-4">#{d.sortOrder}</span>
                  <span className="text-sm font-medium text-ink flex-1">{d.name}</span>
                  <span className="text-xs text-ink-3">{d.windowLabel}</span>
                  {d.isNightShift && (
                    <StatusBadge tone="info" icon={Moon} label={t('shiftDefs.night')} />
                  )}
                </div>
              ))}
            </div>
            </>)}
          </div>
        )
      })}

      {actionError && (
        <div role="alert" className="ui-alert ui-alert-danger flex items-start gap-2">
          <span className="text-bad text-xs flex-1">{actionError}</span>
          <IconButton onClick={() => setActionError(null)} className="ui-btn ui-btn-ghost text-sm leading-none text-bad hover:text-bad-strong"
            label={t('shiftDefs.dismissError')}
            icon={'×'}
          />
        </div>
      )}

      {/* New batch form */}
      {showForm ? (
        <CreateShiftBatchForm pumpId={pumpId} onClose={() => setShowForm(false)} />
      ) : (
        <button
          type="button"
          onClick={() => setShowForm(true)}
          className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-primary hover:text-primary-strong"
        >
          {t('shiftDefs.addSchedule')}
        </button>
      )}
    </div>
  )
}
