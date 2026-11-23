import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { StaffMember } from '../../../api/userApi'
import type { UserRole } from '../../../types/auth'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { StaffRow } from './StaffRow'

const member: StaffMember = {
  id: 11, employeeId: 'EMP-11', fullName: 'Ravi Kumar', phoneNumber: '9876543210', gender: 'MALE',
  nightShiftConsent: true, role: 'OPERATOR', assignedPumpId: 2, status: 'ACTIVE',
  dailyRate: null, shift1HourlyRate: 60, standardHourlyRate: 50,
}

function renderRow(shown: StaffMember, viewerRole: UserRole | undefined, menuOpen = false) {
  return renderWithProviders(
    <StaffRow
      pumpId={2} member={shown} viewerRole={viewerRole} activeShiftDefs={[]}
      openPanel={null} onOpenPanel={vi.fn()} onClosePanel={vi.fn()}
      menuOpen={menuOpen} onMenuChange={vi.fn()} statusPending={false} onToggleStatus={vi.fn()}
    />,
  )
}

function menuLabels() {
  return screen.queryAllByRole('menuitem').map((item) => item.textContent ?? '')
}

describe('StaffRow', () => {
  it('shows the phone number to a viewer the server sent it to', () => {
    renderRow(member, 'OWNER')
    expect(screen.getByText('9876543210')).toBeInTheDocument()
  })

  it('leaves no blank slot when the server withholds the phone and pay rates', () => {
    const { container } = renderRow(
      { ...member, phoneNumber: null, shift1HourlyRate: null, standardHourlyRate: null }, 'MANAGER',
    )
    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByText('EMP-11')).toBeInTheDocument()
    expect(container.querySelectorAll('span:empty')).toHaveLength(0)
    expect(screen.queryByText(/not set/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Staff actions' })).not.toBeInTheDocument()
  })

  it('offers an owner every action on an operator', () => {
    renderRow(member, 'OWNER', true)
    const labels = menuLabels().join(' | ')
    for (const action of ['Edit Details', 'Reset Password', 'Shift Preferences', 'Manage Leave', 'Pay Rates', 'Deactivate Staff']) {
      expect(labels).toContain(action)
    }
  })

  it('offers an admin edit, reset, leave and preferences on an operator, but not pay rates or status', () => {
    renderRow(member, 'ADMIN', true)
    const labels = menuLabels().join(' | ')
    for (const action of ['Edit Details', 'Reset Password', 'Shift Preferences', 'Manage Leave']) {
      expect(labels).toContain(action)
    }
    expect(labels).not.toContain('Pay Rates')
    expect(labels).not.toContain('Deactivate Staff')
  })

  it('does not let an admin edit or reset another admin', () => {
    renderRow({ ...member, role: 'ADMIN' }, 'ADMIN', true)
    const labels = menuLabels().join(' | ')
    expect(labels).not.toContain('Edit Details')
    expect(labels).not.toContain('Reset Password')
    expect(labels).toContain('Manage Leave')
  })
})
