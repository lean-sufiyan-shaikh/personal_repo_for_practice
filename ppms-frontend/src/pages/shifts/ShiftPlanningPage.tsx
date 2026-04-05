import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { usePumpStore } from '../../store/usePumpStore'
import { userApi } from '../../api/userApi'
import { shiftPlanApi } from '../../api/shiftPlanApi'
import type { ShiftPlanEntry } from '../../api/shiftPlanApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import { useAuthStore } from '../../store/authStore'
import { SearchableSelect } from '../../components/SearchableSelect'

// Cyclic color palette for shift rows — index into this by (sortOrder - 1)
const SHIFT_ROW_COLORS = [
  { bg: 'bg-indigo-50',  text: 'text-indigo-700' },
  { bg: 'bg-amber-50',   text: 'text-amber-700'  },
  { bg: 'bg-emerald-50', text: 'text-emerald-700'},
  { bg: 'bg-rose-50',    text: 'text-rose-700'   },
]

const STATUS_BADGE: Record<string, string> = {
  PLANNED:   'bg-slate-100 text-slate-600',
  CONFIRMED: 'bg-emerald-100 text-emerald-700',
  ABSENT:    'bg-red-100 text-red-600',
}

// Return Monday of the week containing `date`
function getMonday(date: Date): Date {
  const d = new Date(date)
  const day = d.getDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setDate(d.getDate() + diff)
  d.setHours(0, 0, 0, 0)
  return d
}

// Use local date components — toISOString() converts to UTC first, causing off-by-one day errors in non-UTC timezones
function toISODate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function formatDay(iso: string): string {
  const d = new Date(iso + 'T00:00:00')
  return d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })
}

// ── Main page ──────────────────────────────────────────────────────────────────

export default function ShiftPlanningPage() {
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const queryClient = useQueryClient()

  const { selectedPumpId } = usePumpStore()
  const [weekStart, setWeekStart] = useState<string>(() => toISODate(getMonday(new Date())))

  // Generate modal state
  const [showGenerate, setShowGenerate] = useState(false)
  const [genDayOps, setGenDayOps] = useState('2')
  const [genNightOps, setGenNightOps] = useState('1')
  const [genError, setGenError] = useState<string | null>(null)

  // Slot editor state
  const [editSlot, setEditSlot] = useState<{ date: string; shiftDefinitionId: number } | null>(null)
  const [slotError, setSlotError] = useState<string | null>(null)
  const [addOpId, setAddOpId] = useState<string>('')

  const pumpId = selectedPumpId

  const { data: staff = [] } = useQuery({
    queryKey: ['staff', pumpId],
    queryFn: () => userApi.getStaff(pumpId!),
    enabled: !!pumpId,
  })
  const operatorStaff = staff.filter(s => s.role === 'OPERATOR' && s.status === 'ACTIVE')
  const staffById = Object.fromEntries(operatorStaff.map(s => [s.id, s]))

  const { data: plan, isLoading: planLoading } = useQuery({
    queryKey: ['shiftPlan', pumpId, weekStart],
    queryFn: () => shiftPlanApi.getPlan(pumpId!, weekStart),
    enabled: !!pumpId,
    retry: false,
  })

  const { data: entries = [] } = useQuery({
    queryKey: ['shiftPlanEntries', plan?.id],
    queryFn: () => shiftPlanApi.getEntries(pumpId!, plan!.id),
    enabled: !!plan?.id,
  })

  // Last day of the displayed week (weekStart + 6 days)
  const weekEnd = (() => {
    const d = new Date(weekStart + 'T00:00:00')
    d.setDate(d.getDate() + 6)
    return toISODate(d)
  })()

  const { data: actualAttendance = [] } = useQuery({
    queryKey: ['actualAttendance', pumpId, weekStart],
    queryFn: () => shiftPlanApi.getActualAttendance(pumpId!, weekStart, weekEnd),
    enabled: !!pumpId,
  })

  const { data: shiftDefinitions = [] } = useQuery({
    queryKey: ['shift-definitions-active', pumpId],
    queryFn: () => shiftDefinitionApi.getActive(pumpId!),
    enabled: !!pumpId,
  })
  const sortedDefs = [...shiftDefinitions].sort((a, b) => a.sortOrder - b.sortOrder)

  const invalidatePlan = () => {
    queryClient.invalidateQueries({ queryKey: ['shiftPlan', pumpId, weekStart] })
    queryClient.invalidateQueries({ queryKey: ['shiftPlanEntries', plan?.id] })
  }

  const generateMutation = useMutation({
    mutationFn: () => shiftPlanApi.generatePlan(pumpId!, weekStart, Number(genDayOps), Number(genNightOps)),
    onSuccess: () => { setShowGenerate(false); setGenError(null); invalidatePlan() },
    onError: (err: any) => setGenError(err?.response?.data?.message ?? 'Failed to generate plan'),
  })

  const publishMutation = useMutation({
    mutationFn: () => shiftPlanApi.publishPlan(pumpId!, plan!.id),
    onSuccess: () => invalidatePlan(),
  })

  const removeEntryMutation = useMutation({
    mutationFn: ({ entryId }: { entryId: number }) =>
      shiftPlanApi.removeEntry(pumpId!, plan!.id, entryId),
    onSuccess: () => { invalidatePlan(); setSlotError(null) },
    onError: (err: any) => setSlotError(err?.response?.data?.message ?? 'Failed to remove'),
  })

  const addEntryMutation = useMutation({
    mutationFn: ({ opId }: { opId: number }) =>
      shiftPlanApi.addEntry(pumpId!, plan!.id, editSlot!.date, editSlot!.shiftDefinitionId, opId),
    onSuccess: () => { setAddOpId(''); setSlotError(null); invalidatePlan() },
    onError: (err: any) => setSlotError(err?.response?.data?.message ?? 'Failed to add operator'),
  })

  // Navigation
  const prevWeek = () => {
    const d = new Date(weekStart + 'T00:00:00')
    d.setDate(d.getDate() - 7)
    setWeekStart(toISODate(d))
  }
  const nextWeek = () => {
    const d = new Date(weekStart + 'T00:00:00')
    d.setDate(d.getDate() + 7)
    setWeekStart(toISODate(d))
  }

  // Build 7 day columns
  const days: string[] = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(weekStart + 'T00:00:00')
    d.setDate(d.getDate() + i)
    return toISODate(d)
  })

  const today = toISODate(new Date())

  // The week is entirely in the past when its last day is before today
  const isWeekEntirelyPast = weekEnd < today

  // Build entry lookup: date → shiftDefinitionId → entries[]
  const entryMap: Record<string, Record<number, ShiftPlanEntry[]>> = {}
  for (const e of entries) {
    entryMap[e.shiftDate] ??= {}
    entryMap[e.shiftDate][e.shiftDefinitionId] ??= []
    entryMap[e.shiftDate][e.shiftDefinitionId].push(e)
  }

  // Build actual attendance lookup: date → shiftDefinitionId → distinct operatorUserIds[]
  const actualMap: Record<string, Record<number, number[]>> = {}
  for (const a of actualAttendance) {
    actualMap[a.shiftDate] ??= {}
    actualMap[a.shiftDate][a.shiftDefinitionId] ??= []
    if (!actualMap[a.shiftDate][a.shiftDefinitionId].includes(a.operatorUserId)) {
      actualMap[a.shiftDate][a.shiftDefinitionId].push(a.operatorUserId)
    }
  }

  const isEditingSlot = (date: string, shiftDefinitionId: number) =>
    editSlot?.date === date && editSlot?.shiftDefinitionId === shiftDefinitionId

  if (!isOwnerOrAdmin && !pumpId) {
    return (
      <div className="p-6 w-full lg:max-w-[85%] 2xl:max-w-[60%]">
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-amber-700 text-sm">
          You are not assigned to any pump. Ask the Owner or Admin to assign you.
        </div>
      </div>
    )
  }

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* Page title */}
      <div>
        <h2 className="text-xl font-bold text-slate-800">Shift Planning</h2>
        <p className="text-slate-500 text-sm mt-1">
          Plan weekly operator schedules. Published plans pre-fill shift opening.
        </p>
      </div>

      {pumpId && (
        <>
          {/* Week navigator + actions */}
          <div className="bg-white rounded-xl border border-slate-200 px-5 py-4 flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <button onClick={prevWeek}
                className="p-1.5 rounded-lg border border-slate-200 hover:bg-slate-50 text-slate-500 transition-colors">
                ←
              </button>
              <span className="text-sm font-semibold text-slate-700">
                Week of {new Date(weekStart + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' })}
              </span>
              <button onClick={nextWeek}
                className="p-1.5 rounded-lg border border-slate-200 hover:bg-slate-50 text-slate-500 transition-colors">
                →
              </button>
            </div>

            <div className="flex items-center gap-2">
              {plan && (
                <span className={`text-xs font-medium px-2.5 py-1 rounded-full ${
                  plan.status === 'PUBLISHED'
                    ? 'bg-emerald-100 text-emerald-700'
                    : 'bg-amber-100 text-amber-700'
                }`}>
                  {plan.status === 'PUBLISHED' ? '✓ Published' : '⬤ Draft'}
                </span>
              )}
              {isOwnerOrAdmin && (
                <>
                  <button
                    onClick={() => { setShowGenerate(true); setGenError(null) }}
                    disabled={isWeekEntirelyPast}
                    title={isWeekEntirelyPast ? 'Cannot generate a plan for a past week' : undefined}
                    className="text-sm font-medium bg-blue-600 hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed text-white px-3 py-1.5 rounded-lg transition-colors"
                  >
                    {plan ? 'Regenerate' : '✦ Auto-generate'}
                  </button>
                  {plan && plan.status === 'DRAFT' && (
                    <button
                      onClick={() => publishMutation.mutate()}
                      disabled={publishMutation.isPending}
                      className="text-sm font-medium bg-emerald-600 hover:bg-emerald-700 disabled:bg-emerald-300 text-white px-3 py-1.5 rounded-lg transition-colors"
                    >
                      {publishMutation.isPending ? 'Publishing…' : 'Publish Week'}
                    </button>
                  )}
                </>
              )}
            </div>
          </div>

          {/* Auto-generate modal */}
          {showGenerate && (
            <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
              <div className="bg-white rounded-2xl shadow-2xl w-full max-w-sm mx-4 p-6 space-y-4">
                <h3 className="text-base font-bold text-slate-800">Auto-generate schedule</h3>
                <p className="text-xs text-slate-500">
                  The system will assign operators based on their preferences, leave dates, and fairness.
                  You can tweak the schedule afterwards.
                </p>
                <div className="space-y-3">
                  <div>
                    <label className="block text-xs text-slate-500 mb-1">
                      Operators per Morning / Evening shift
                    </label>
                    <input type="number" min="1" max="20" value={genDayOps}
                      onChange={e => setGenDayOps(e.target.value)}
                      className="w-24 border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                  <div>
                    <label className="block text-xs text-slate-500 mb-1">
                      Operators per Night shift (12 AM – 8 AM)
                    </label>
                    <input type="number" min="1" max="20" value={genNightOps}
                      onChange={e => setGenNightOps(e.target.value)}
                      className="w-24 border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                </div>
                {genError && <p className="text-red-500 text-xs">{genError}</p>}
                <div className="flex gap-2">
                  <button
                    onClick={() => generateMutation.mutate()}
                    disabled={generateMutation.isPending}
                    className="bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
                  >
                    {generateMutation.isPending ? 'Generating…' : 'Generate'}
                  </button>
                  <button onClick={() => setShowGenerate(false)}
                    className="text-sm text-slate-500 hover:text-slate-700 px-4 py-2 rounded-lg border border-slate-200 transition-colors">
                    Cancel
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Planning grid */}
          {planLoading ? (
            <div className="bg-white rounded-xl border border-slate-200 p-8 text-sm text-slate-400 text-center">
              Loading plan…
            </div>
          ) : (
            <>
              {/* No-plan notice for future weeks */}
              {!plan && isOwnerOrAdmin && days.some(d => d >= today) && (
                <div className="bg-slate-50 border border-slate-200 rounded-xl px-5 py-3 text-sm text-slate-500 text-center">
                  No plan generated for this week yet.{' '}
                  <span className="text-blue-600 font-medium">Click "Auto-generate" to create a schedule.</span>
                </div>
              )}

              <div className="bg-white rounded-xl border border-slate-200 overflow-x-auto">
                <table className="w-full text-xs border-collapse min-w-[640px]">
                  <thead>
                    <tr className="border-b border-slate-100">
                      <th className="w-28 text-left px-3 py-3 text-xs font-semibold text-slate-500 uppercase tracking-wide">
                        Shift
                      </th>
                      {days.map(day => (
                        <th key={day} className={`px-2 py-3 text-center font-semibold ${day < today ? 'text-slate-400' : 'text-slate-600'}`}>
                          {formatDay(day)}
                          {day < today && (
                            <div className="text-xs font-normal text-slate-300 mt-0.5">actual</div>
                          )}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {sortedDefs.length === 0 ? (
                      <tr>
                        <td colSpan={8} className="px-3 py-6 text-center text-sm text-slate-400">
                          No shift definitions configured for this pump. Set them up in Pump Setup first.
                        </td>
                      </tr>
                    ) : sortedDefs.map((def, defIdx) => {
                      const colors = SHIFT_ROW_COLORS[defIdx % SHIFT_ROW_COLORS.length]
                      return (
                        <tr key={def.id} className={`border-b border-slate-100 ${colors.bg}`}>
                          <td className={`px-3 py-3 font-semibold ${colors.text} align-top`}>
                            {def.name}
                            {def.isNightShift && (
                              <span className="ml-1 text-[10px] bg-indigo-100 text-indigo-600 px-1.5 py-0.5 rounded-full font-medium">Night</span>
                            )}
                            <div className="font-normal text-slate-400 text-xs mt-0.5 leading-tight">
                              {def.windowLabel}
                            </div>
                          </td>
                          {days.map(day => {
                            const isPast = day < today
                            const slotEntries = entryMap[day]?.[def.id] ?? []
                            const actualOpIds = actualMap[day]?.[def.id] ?? []
                            const isEditing = isEditingSlot(day, def.id)
                            const availableToAdd = operatorStaff.filter(
                              s => !slotEntries.some(e => e.operatorUserId === s.id)
                            )

                            return (
                              <td key={day} className={`px-2 py-2 align-top ${isPast ? 'opacity-80' : ''}`}>
                                <div className="space-y-1">
                                  {isPast ? (
                                    // Past date: show who actually worked (from real Shift records)
                                    actualOpIds.length > 0 ? (
                                      actualOpIds.map(opId => (
                                        <div key={opId} className="flex items-center gap-1">
                                          <span className="truncate text-slate-600 font-medium">
                                            {staffById[opId]?.fullName ?? `#${opId}`}
                                          </span>
                                          <span className="ml-auto text-xs px-1.5 py-0.5 rounded-full shrink-0 bg-emerald-100 text-emerald-700">
                                            ✓
                                          </span>
                                        </div>
                                      ))
                                    ) : (
                                      <span className="text-slate-300 text-xs italic">No shift</span>
                                    )
                                  ) : (
                                    // Future / today: show plan entries
                                    <>
                                      {slotEntries.map(entry => (
                                        <div key={entry.id} className="flex items-center gap-1 group">
                                          <span className="truncate text-slate-700 font-medium">
                                            {staffById[entry.operatorUserId]?.fullName ?? `#${entry.operatorUserId}`}
                                          </span>
                                          <span className={`ml-auto text-xs px-1.5 py-0.5 rounded-full shrink-0 ${STATUS_BADGE[entry.status]}`}>
                                            {entry.status === 'PLANNED' ? '●' : entry.status === 'CONFIRMED' ? '✓' : '✗'}
                                          </span>
                                          {isOwnerOrAdmin && entry.status === 'PLANNED' && (
                                            <button
                                              type="button"
                                              onClick={() => removeEntryMutation.mutate({ entryId: entry.id })}
                                              disabled={removeEntryMutation.isPending}
                                              className="opacity-0 group-hover:opacity-100 text-slate-300 hover:text-red-500 transition-all ml-0.5 shrink-0"
                                              title="Remove"
                                            >
                                              ✕
                                            </button>
                                          )}
                                        </div>
                                      ))}

                                      {/* Add operator to slot */}
                                      {isOwnerOrAdmin && plan && !isEditing && (
                                        <button
                                          type="button"
                                          onClick={() => { setEditSlot({ date: day, shiftDefinitionId: def.id }); setSlotError(null); setAddOpId('') }}
                                          className="text-slate-300 hover:text-blue-500 transition-colors text-xs"
                                          title="Add operator"
                                        >
                                          + Add
                                        </button>
                                      )}

                                      {isEditing && (
                                        <div className="mt-1 space-y-1">
                                          <SearchableSelect
                                            value={addOpId}
                                            onChange={v => setAddOpId(v)}
                                            placeholder="— pick operator —"
                                            size="sm"
                                            options={availableToAdd.map(s => ({ value: s.id.toString(), label: s.fullName }))}
                                          />
                                          <div className="flex gap-1">
                                            <button
                                              type="button"
                                              onClick={() => addOpId && addEntryMutation.mutate({ opId: Number(addOpId) })}
                                              disabled={!addOpId || addEntryMutation.isPending}
                                              className="bg-blue-600 disabled:bg-blue-300 text-white px-2 py-0.5 rounded text-xs transition-colors"
                                            >
                                              Add
                                            </button>
                                            <button
                                              type="button"
                                              onClick={() => { setEditSlot(null); setSlotError(null) }}
                                              className="text-slate-500 px-2 py-0.5 rounded text-xs border border-slate-200 transition-colors"
                                            >
                                              ✕
                                            </button>
                                          </div>
                                          {slotError && <p className="text-red-500 text-xs">{slotError}</p>}
                                        </div>
                                      )}
                                    </>
                                  )}
                                </div>
                              </td>
                            )
                          })}
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {/* Legend */}
          <div className="flex flex-wrap gap-4 text-xs text-slate-500">
            <span className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-slate-400 inline-block" /> Planned
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-500 inline-block" /> Confirmed (showed up)
            </span>
            <span className="flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-red-400 inline-block" /> Absent (didn't show)
            </span>
          </div>
        </>
      )}
    </div>
  )
}
