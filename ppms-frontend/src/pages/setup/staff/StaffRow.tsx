import { useRef, useState } from 'react'
import { CircleOff, Settings, CalendarDays, RotateCcw, Pencil, KeyRound, ArrowRightLeft } from 'lucide-react'
import type { StaffMember } from '../../../api/userApi'
import type { ShiftDefinition } from '../../../api/shiftDefinitionApi'
import { RowActionMenu } from '../../../components/RowActionMenu'
import type { RowActionMenuItem } from '../../../components/RowActionMenu'
import { useRowPanelFocus } from '../../../hooks/useRowPanelFocus'
import { ROLE_BADGE } from './staffForm'
import type { StaffPanel } from './staffForm'
import { EditStaffPanel } from './EditStaffPanel'
import { PreferencesPanel } from './PreferencesPanel'
import { PayRatesPanel } from './PayRatesPanel'
import { LeavePanel } from './LeavePanel'
import { MoveToPumpPanel } from './MoveToPumpPanel'
import { codeLabel } from '../../../constants/labels'
import { ResetPasswordDialog } from '../../../components/ResetPasswordDialog'
import { StatusBadge } from '../../../components/StatusBadge'
import { canManageStaffMember, canPerformAction } from '../../../permissions/permissions'
import type { UserRole } from '../../../types/auth'

interface Props {
  pumpId: number
  member: StaffMember
  viewerRole: UserRole | undefined
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
  pumpId, member, viewerRole, activeShiftDefs, openPanel, onOpenPanel, onClosePanel,
  menuOpen, onMenuChange, statusPending, onToggleStatus,
}: Props) {
  const isInactive = member.status === 'INACTIVE'
  const isPrefOpen  = openPanel === 'preferences'
  const isLeaveOpen = openPanel === 'leave'
  const isOperator  = member.role === 'OPERATOR'
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  useRowPanelFocus(openPanel, panelRef, menuButtonRef)
  const payRatesMissing = member.role === 'OPERATOR' ? member.shift1HourlyRate == null : member.dailyRate == null
  const [resetDialogOpen, setResetDialogOpen] = useState(false)

  const managesMember = canManageStaffMember(viewerRole, member.role)
  const canEditDetails = managesMember && canPerformAction('staff:edit-details', viewerRole)
  const canResetPassword = managesMember && canPerformAction('staff:reset-password', viewerRole)
  const canSetPreferences = isOperator && canPerformAction('shiftPlan:manage-preferences', viewerRole)
  const canManageLeave = canPerformAction('shiftPlan:manage-leaves', viewerRole)
  const canSetPayRates = managesMember && canPerformAction('staff:manage-pay-rates', viewerRole)
  const canChangeStatus = managesMember && canPerformAction('staff:change-status', viewerRole)
  const canMoveToPump = managesMember && canPerformAction('staff:move-pump', viewerRole)

  const menuItems: RowActionMenuItem[] = [
    ...(canEditDetails ? [{ key: 'edit', label: 'Edit Details', description: 'Update name, phone number, or role', icon: <Pencil size={14} strokeWidth={2} />, className: 'text-ink hover:bg-info-bg hover:text-info', onSelect: () => onOpenPanel('edit') }] : []),
    ...(canResetPassword ? [{ key: 'reset-password', label: 'Reset Password', description: 'Set a temporary password they must change at next sign-in', icon: <KeyRound size={14} strokeWidth={2} />, className: 'text-ink hover:bg-warn-bg hover:text-warn', onSelect: () => { onMenuChange(null); setResetDialogOpen(true) } }] : []),
    ...(canSetPreferences ? [{ key: 'preferences', label: 'Shift Preferences', description: 'Set preferred shift and weekly day off', icon: <Settings size={14} strokeWidth={2} />, className: 'text-ink hover:bg-info-bg hover:text-info', onSelect: () => onOpenPanel('preferences') }] : []),
    ...(canManageLeave ? [{ key: 'leave', label: 'Manage Leave', description: 'Add or remove leave dates for this staff member', icon: <CalendarDays size={14} strokeWidth={2} />, className: 'text-ink hover:bg-warn-bg hover:text-warn', onSelect: () => onOpenPanel('leave') }] : []),
    ...(canSetPayRates ? [{ key: 'rates', label: payRatesMissing ? 'Pay Rates (not set)' : 'Pay Rates', description: 'Configure hourly or daily pay rates', icon: '₹', className: 'text-ink hover:bg-ok-bg hover:text-ok', onSelect: () => onOpenPanel('rates') }] : []),
    ...(canMoveToPump ? [{ key: 'move', label: 'Move to Another Pump', description: 'Assign this staff member to another of your pumps', icon: <ArrowRightLeft size={14} strokeWidth={2} />, className: 'text-ink hover:bg-info-bg hover:text-info', onSelect: () => onOpenPanel('move') }] : []),
    ...(canChangeStatus ? [{
      key: 'status',
      label: isInactive ? 'Activate Staff' : 'Deactivate Staff',
      description: isInactive ? 'Allow this staff member to work again' : 'Temporarily remove this staff member from active use',
      icon: isInactive ? <RotateCcw size={14} strokeWidth={2} /> : <CircleOff size={14} strokeWidth={2} />,
      className: isInactive ? 'text-ok hover:bg-ok-bg' : 'text-bad hover:bg-bad-bg',
      onSelect: onToggleStatus,
      disabled: statusPending,
    }] : []),
  ]

  return (
    <div
      key={member.id}
      className={`ui-card-plain relative ${menuOpen ? 'z-20' : 'z-0'} ${isInactive ? 'border-border opacity-70' : 'border-border'}`}
    >
      {/* Staff row */}
      <div className="flex flex-col gap-3 px-4 py-3 md:flex-row md:items-center">
        <div aria-hidden="true" className={`h-10 w-10 shrink-0 rounded-full flex items-center justify-center text-sm font-bold ${
          ROLE_BADGE[member.role] ?? 'bg-surface-sunken text-ink-2'
        }`}>
          {member.fullName.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
            <div className="min-w-0">
              <p className="truncate text-base font-semibold text-ink">{member.fullName}</p>
              <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-3">
                {member.phoneNumber ? <span>{member.phoneNumber}</span> : null}
                {member.employeeId ? <span>{member.employeeId}</span> : null}
                {member.gender ? (
                  <span>{member.gender.charAt(0) + member.gender.slice(1).toLowerCase()}</span>
                ) : null}
                {member.role === 'OPERATOR' && member.gender === 'FEMALE' ? (
                  <span className={member.nightShiftConsent ? 'text-ok' : 'text-warn'}>
                    {member.nightShiftConsent ? 'Night shift okay' : 'No night shift'}
                  </span>
                ) : null}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <span className={`text-xs px-2 py-0.5 rounded-badge font-semibold ${ROLE_BADGE[member.role] ?? 'bg-surface-sunken text-ink-2'}`}>
                {codeLabel(member.role)}
              </span>
              {isInactive && (
                <StatusBadge tone="bad" icon={CircleOff} label="Inactive" />
              )}
            </div>
          </div>
        </div>
        {menuItems.length > 0 && (
          <div className="md:ml-auto">
            <RowActionMenu
              label="Staff actions"
              open={menuOpen}
              onOpenChange={(open) => onMenuChange(open ? member.id : null)}
              triggerRef={menuButtonRef}
              triggerClassName="flex h-10 w-10 items-center justify-center rounded-card border border-border bg-bg text-lg leading-none text-ink-2 transition-colors hover:border-control hover:bg-surface hover:text-ink"
              menuClassName="absolute right-0 top-full z-6 mt-2 w-64 overflow-hidden rounded-card border border-border bg-surface p-1.5 shadow-overlay"
              items={menuItems}
            />
          </div>
        )}
      </div>

      <div ref={panelRef}>
      {/* Edit details panel */}
      {openPanel === 'edit' && (
        <EditStaffPanel pumpId={pumpId} member={member} canAssignAdmin={canPerformAction('staff:create-admin', viewerRole)} onClose={onClosePanel} />

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

      {openPanel === 'move' && (
        <MoveToPumpPanel pumpId={pumpId} member={member} onClose={onClosePanel} />
      )}
      </div>

      {resetDialogOpen && (
        <ResetPasswordDialog userId={member.id} userName={member.fullName} onClose={() => setResetDialogOpen(false)} />
      )}
    </div>
  )
}
