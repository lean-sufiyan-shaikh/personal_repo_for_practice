import { Check, X, Circle, Moon } from 'lucide-react'
import { StatusBadge } from '../../../components/StatusBadge'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { SkeletonTable } from '../../../components/Skeleton'
import { Reveal } from '../../../components/Reveal'
import { IconButton } from '../../../components/IconButton'
import { SHIFT_ROW_COLORS, STATUS_BADGE, formatDay } from './planningHelpers'
import type { ShiftPlanning } from './useShiftPlanning'
import { ScrollRegion } from '../../../components/ScrollRegion'

export function PlanningGrid({ form }: { form: ShiftPlanning }) {
  const { isOwnerOrAdmin, setEditSlot, slotError, setSlotError, addOpId, setAddOpId, operatorStaff, staffById, plan, planLoading, sortedDefs, removeEntryMutation, addEntryMutation, days, today, entryMap, actualMap, isEditingSlot } = form
  return (
             <Reveal delay={200}>
             {planLoading ? (
               <div className="ui-card px-5 py-4"><SkeletonTable rows={4} cols={7} /></div>
             ) : (
               <>
                 {/* No-plan notice for future weeks */}
                 {!plan && days.some(d => d >= today) && (
                   <div className="ui-card-plain ui-card-muted px-5 py-3 text-sm text-ink-3 text-center">
                     {isOwnerOrAdmin ? (
                       <>
                         No plan generated for this week yet.{' '}
                         <span className="text-primary font-medium">Click "Auto-generate" to create a schedule.</span>
                       </>
                     ) : 'No plan published for this week yet.'}
                   </div>
                 )}

                 <ScrollRegion label="Weekly shift plan" className="ui-card p-0 overflow-x-auto">
                   <table className="w-full text-xs border-collapse min-w-[640px]">
                     <thead>
                       <tr className="border-b border-border">
                         <th scope="col" className="w-28 text-left px-3 py-3 text-xs font-semibold text-ink-3 uppercase tracking-wide">
                           Shift
                         </th>
                         {days.map(day => (
                           <th scope="col" key={day} className={`px-2 py-3 text-center font-semibold ${day < today ? 'text-ink-3' : 'text-ink-2'}`}>
                             {formatDay(day)}
                             {day < today && (
                               <div className="text-xs font-normal text-ink-3 mt-0.5">actual</div>
                             )}
                           </th>
                         ))}
                       </tr>
                     </thead>
                     <tbody>
                       {sortedDefs.length === 0 ? (
                         <tr>
                           <td colSpan={8} className="px-3 py-6 text-center text-sm text-ink-3">
                             No shift definitions configured for this pump. Set them up in Pump Setup first.
                           </td>
                         </tr>
                       ) : sortedDefs.map((def, defIdx) => {
                         const colors = SHIFT_ROW_COLORS[defIdx % SHIFT_ROW_COLORS.length]
                         return (
                           <tr key={def.id} className={`border-b border-border ${colors.bg}`}>
                             <td className={`px-3 py-3 font-semibold ${colors.text} align-top`}>
                               {def.name}
                               {def.isNightShift && (
                                 <StatusBadge tone="info" icon={Moon} label="Night" className="ml-1" />
                               )}
                               <div className="font-normal text-ink-3 text-xs mt-0.5 leading-tight">
                                 {def.windowLabel}
                               </div>
                             </td>
                             {days.map(day => {
                               const slotEntries = entryMap[day]?.[def.id] ?? []
                               const actualOpIds = actualMap[day]?.[def.id] ?? []
                               // Show actual attendance for past days, or for today's slots where a shift has already started
                               const isPast = day < today || (day === today && actualOpIds.length > 0)
                               const isEditing = isEditingSlot(day, def.id)
                               const availableToAdd = operatorStaff.filter(
                                 s => !slotEntries.some(e => e.operatorUserId === s.id)
                               )

                               return (
                                 <td key={day} className={`px-2 py-2 align-top ${isPast ? 'opacity-80' : ''}`}>
                                   <div className="space-y-1">
                                     {isPast ? (
                                       // Past date: show confirmed (actually worked) + absent (planned but didn't show)
                                       (() => {
                                         const absentEntries = slotEntries.filter(
                                           e => !actualOpIds.includes(e.operatorUserId)
                                         )
                                         if (actualOpIds.length === 0 && absentEntries.length === 0) {
                                           return <span className="text-ink-3 text-xs italic">No shift</span>
                                         }
                                         return (
                                           <>
                                             {actualOpIds.map(opId => (
                                               <div key={opId} className="flex items-center gap-1">
                                                 <span className="truncate text-ink-2 font-medium">
                                                   {staffById[opId]?.fullName ?? `#${opId}`}
                                                 </span>
                                                 <span className="ml-auto flex items-center px-1.5 py-0.5 rounded-full shrink-0 bg-ok-bg text-ok">
                                                   <Check size={10} strokeWidth={2.5} />
                                                 </span>
                                               </div>
                                             ))}
                                             {absentEntries.map(entry => (
                                               <div key={`absent-${entry.id}`} className="flex items-center gap-1">
                                                 <span className="truncate text-ink-3 font-medium">
                                                   {staffById[entry.operatorUserId]?.fullName ?? `#${entry.operatorUserId}`}
                                                 </span>
                                                 <span className="ml-auto flex items-center px-1.5 py-0.5 rounded-full shrink-0 bg-bad-bg text-bad">
                                                   <X size={10} strokeWidth={2.5} />
                                                 </span>
                                               </div>
                                             ))}
                                           </>
                                         )
                                       })()
                                     ) : (
                                       // Future / today: show plan entries
                                       <>
                                         {slotEntries.map(entry => (
                                           <div key={entry.id} className="flex items-center gap-1 group">
                                             <span className="truncate text-ink font-medium">
                                               {staffById[entry.operatorUserId]?.fullName ?? `#${entry.operatorUserId}`}
                                             </span>
                                             <span className={`ml-auto text-xs px-1.5 py-0.5 rounded-full shrink-0 ${STATUS_BADGE[entry.status]}`}>
                                               {entry.status === 'PLANNED' ? <Circle size={8} fill="currentColor" strokeWidth={0} /> : entry.status === 'CONFIRMED' ? <Check size={10} strokeWidth={2.5} /> : <X size={10} strokeWidth={2.5} />}
                                             </span>
                                             {isOwnerOrAdmin && entry.status === 'PLANNED' && (
                                               <IconButton
                                                 onClick={() => removeEntryMutation.mutate({ entryId: entry.id })}
                                                 disabled={removeEntryMutation.isPending}
                                                 className="opacity-0 group-hover:opacity-100 focus-visible:opacity-100 text-ink-3 hover:text-bad transition-all ml-0.5 shrink-0"
    label="Remove from plan"
    icon={<X size={11} strokeWidth={2} />}
    />
                                             )}
                                           </div>
                                         ))}

                                         {/* Add operator to slot */}
                                         {isOwnerOrAdmin && plan && !isEditing && (
                                           <button
                                             type="button"
                                             onClick={() => { setEditSlot({ date: day, shiftDefinitionId: def.id }); setSlotError(null); setAddOpId('') }}
                                             className="text-ink-3 hover:text-primary transition-colors text-xs"
                                             title="Add operator"
                                           >
                                             + Add
                                           </button>
                                         )}

                                         {isEditing && (
                                           <div className="mt-1 space-y-1">
                                             <SearchableSelect label="Operator to add"
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
                                                 className="ui-btn ui-btn-primary min-h-0 px-2 py-1 text-xs disabled:opacity-60"
                                               >
                                                 Add
                                               </button>
                                               <IconButton
                                                 onClick={() => { setEditSlot(null); setSlotError(null) }}
                                                 className="ui-btn ui-btn-secondary px-2 py-1 text-xs"
    label="Cancel"
    icon={<X size={12} strokeWidth={2} />}
    />
                                             </div>
                                             {slotError && <p role="alert" className="ui-error-text">{slotError}</p>}
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
                 </ScrollRegion>
               </>
             )}
             </Reveal>
  )
}
