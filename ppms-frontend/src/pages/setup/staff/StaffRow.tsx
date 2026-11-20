import { CircleOff, Settings, CalendarDays, RotateCcw, Pencil } from 'lucide-react'
import type { StaffMember } from '../../../api/userApi'
import type { ShiftDefinition } from '../../../api/shiftDefinitionApi'
import { IconButton } from '../../../components/IconButton'
import { ROLE_BADGE } from './staffForm'
import type { StaffPanel } from './staffForm'
import { EditStaffPanel } from './EditStaffPanel'
import { PreferencesPanel } from './PreferencesPanel'
import { PayRatesPanel } from './PayRatesPanel'
import { LeavePanel } from './LeavePanel'

interface Props {
  pumpId: number
  member: StaffMember
  isOwner: boolean
  isOwnerOrAdmin: boolean
  activeShiftDefs: ShiftDefinition[]
  openPanel: StaffPanel | null
  onOpenPanel: (panel: StaffPanel) => void
  onClosePanel: () => void
  menuOpen: boolean
  onMenuChange: (userId: number | null) => void
  statusPending: boolean
  onToggleStatus: () => void
}

export function StaffRow({
  pumpId, member, isOwner, isOwnerOrAdmin, activeShiftDefs, openPanel, onOpenPanel, onClosePanel,
  menuOpen, onMenuChange, statusPending, onToggleStatus,
}: Props) {
  const isInactive = member.status === 'INACTIVE'
  const isPrefOpen  = openPanel === 'preferences'
  const isLeaveOpen = openPanel === 'leave'
  const isOperator  = member.role === 'OPERATOR'
  return (
    <div
      key={member.id}
      className={`ui-card-plain relative ${menuOpen ? 'z-20' : 'z-0'} ${isInactive ? 'border-slate-100 opacity-70' : 'border-slate-200'}`}
    >
      {/* Staff row */}
      <div className="flex flex-col gap-3 px-4 py-3 md:flex-row md:items-center">
        <div className={`h-10 w-10 shrink-0 rounded-full flex items-center justify-center text-sm font-bold shadow-sm ${
          ROLE_BADGE[member.role]?.replace('text-', 'bg-').replace('-700', '-200') ?? 'bg-slate-100'
        }`}>
          {member.fullName.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
            <div className="min-w-0">
              <p className="truncate text-[15px] font-semibold text-slate-800">{member.fullName}</p>
              <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
                <span>{member.phoneNumber}</span>
                {member.employeeId ? <span>{member.employeeId}</span> : null}
                {member.gender ? (
                  <span>{member.gender.charAt(0) + member.gender.slice(1).toLowerCase()}</span>
                ) : null}
                {member.role === 'OPERATOR' && member.gender === 'FEMALE' ? (
                  <span className={member.nightShiftConsent ? 'text-emerald-600' : 'text-amber-700'}>
                    {member.nightShiftConsent ? 'Night shift okay' : 'No night shift'}
                  </span>
                ) : null}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <span className={`text-[11px] px-2.5 py-1 rounded-full font-semibold tracking-[0.08em] ${ROLE_BADGE[member.role] ?? 'bg-slate-100 text-slate-600'}`}>
                {member.role}
              </span>
              {isInactive && (
                <span className="text-[11px] px-2.5 py-1 rounded-full font-semibold tracking-[0.08em] bg-red-100 text-red-600">Inactive</span>
              )}
            </div>
          </div>
        </div>
        {isOwnerOrAdmin && (
          <div className="md:ml-auto">
            <div className="relative">
              <IconButton
                onClick={() => onMenuChange(menuOpen ? null : member.id)}
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-lg leading-none text-slate-400 transition-colors hover:border-slate-300 hover:bg-white hover:text-slate-600"
 label="Staff actions"
 icon={'⋮'}
 />
              {menuOpen && (
                <>
                  <div
                    className="fixed inset-0 z-[5]"
                    onClick={() => onMenuChange(null)}
                  />
                  <div className="absolute right-0 top-full z-[6] mt-2 w-64 overflow-hidden rounded-2xl border border-slate-200 bg-white p-1.5 shadow-[0_18px_40px_rgba(15,23,42,0.14)] backdrop-blur">
                    <button
                      type="button"
                      onClick={() => onOpenPanel('edit')}
                      className={`flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                        openPanel === 'edit'
                          ? 'bg-violet-50 text-violet-700'
                          : 'text-slate-700 hover:bg-violet-50 hover:text-violet-700'
                      }`}
                    >
                      <span className="mt-0.5 shrink-0 leading-none"><Pencil size={14} strokeWidth={2} /></span>
                      <span className="min-w-0">
                        <span className="block text-xs font-semibold leading-5">Edit Details</span>
                        <span className="block text-[11px] leading-4 text-slate-500">Update name, phone number, or role</span>
                      </span>
                    </button>
                    {isOwner && isOperator && (
                      <button
                        type="button"
                        onClick={() => onOpenPanel('preferences')}
                        className={`flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                          isPrefOpen ? 'bg-blue-50 text-blue-700' : 'text-slate-700 hover:bg-blue-50 hover:text-blue-700'
                        }`}
                      >
                        <span className="mt-0.5 shrink-0 leading-none"><Settings size={14} strokeWidth={2} /></span>
                        <span className="min-w-0">
                          <span className="block text-xs font-semibold leading-5">Shift Preferences</span>
                          <span className="block text-[11px] leading-4 text-slate-500">Set preferred shift and weekly day off</span>
                        </span>
                      </button>
                    )}
                    {isOwner && (
                      <button
                        type="button"
                        onClick={() => onOpenPanel('leave')}
                        className={`flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                          isLeaveOpen ? 'bg-amber-50 text-amber-700' : 'text-slate-700 hover:bg-amber-50 hover:text-amber-700'
                        }`}
                      >
                        <span className="mt-0.5 shrink-0 leading-none"><CalendarDays size={14} strokeWidth={2} /></span>
                        <span className="min-w-0">
                          <span className="block text-xs font-semibold leading-5">Manage Leave</span>
                          <span className="block text-[11px] leading-4 text-slate-500">Add or remove leave dates for this staff member</span>
                        </span>
                      </button>
                    )}
                    {isOwner && (
                      <button
                        type="button"
                        onClick={() => onOpenPanel('rates')}
                        className={`flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                          openPanel === 'rates'
                            ? 'bg-emerald-50 text-emerald-700'
                            : 'text-slate-700 hover:bg-emerald-50 hover:text-emerald-700'
                        }`}
                      >
                        <span className="mt-0.5 shrink-0 text-sm leading-none">₹</span>
                        <span className="min-w-0">
                          <span className="block text-xs font-semibold leading-5">
                            Pay Rates{(member.role === 'OPERATOR' ? member.shift1HourlyRate == null : member.dailyRate == null) ? (
                              <span className="ml-1 text-red-400">!</span>
                            ) : null}
                          </span>
                          <span className="block text-[11px] leading-4 text-slate-500">Configure hourly or daily pay rates</span>
                        </span>
                      </button>
                    )}
                    {isOwner && (
                      <button
                        type="button"
                        onClick={() => {
                          onMenuChange(null)
                          onToggleStatus()
                        }}
                        disabled={statusPending}
                        className={`flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left transition-colors disabled:opacity-50 ${
                          isInactive ? 'text-emerald-700 hover:bg-emerald-50' : 'text-red-600 hover:bg-red-50'
                        }`}
                      >
                        <span className="mt-0.5 shrink-0 leading-none">{isInactive ? <RotateCcw size={14} strokeWidth={2} /> : <CircleOff size={14} strokeWidth={2} />}</span>
                        <span className="min-w-0">
                          <span className="block text-xs font-semibold leading-5">{isInactive ? 'Activate Staff' : 'Deactivate Staff'}</span>
                          <span className="block text-[11px] leading-4 text-slate-500">
                            {isInactive ? 'Allow this staff member to work again' : 'Temporarily remove this staff member from active use'}
                          </span>
                        </span>
                      </button>
                    )}
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Edit details panel */}
      {openPanel === 'edit' && (
        <EditStaffPanel pumpId={pumpId} member={member} isOwner={isOwner} onClose={onClosePanel} />
      )}

      {/* Preferences panel */}
      {isPrefOpen && (
        <PreferencesPanel pumpId={pumpId} member={member} activeShiftDefs={activeShiftDefs} onClose={onClosePanel} />
      )}

      {/* Pay Rates panel */}
      {openPanel === 'rates' && (
        <PayRatesPanel pumpId={pumpId} member={member} onClose={onClosePanel} />
      )}

      {/* Leave management panel */}
      {isLeaveOpen && (
        <LeavePanel pumpId={pumpId} member={member} onClose={onClosePanel} />
      )}
    </div>
  )
}
