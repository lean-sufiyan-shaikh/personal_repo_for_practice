import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shiftApi } from '../../../api/shiftApi'
import { userApi } from '../../../api/userApi'
import type { StaffMember } from '../../../api/userApi'
import { useAuthStore } from '../../../store/authStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { Shift, ShiftOperations } from '../../../types/shift'
import { ShiftOperationsModal } from './ShiftOperationsModal'

vi.mock('../../../api/shiftApi', () => ({
  shiftApi: { getOperations: vi.fn(), swapNozzle: vi.fn(), startRelief: vi.fn(), endRelief: vi.fn(), addOperator: vi.fn(), removeOperator: vi.fn() },
}))
vi.mock('../../../api/userApi', () => ({ userApi: { getOperators: vi.fn() } }))

const shift = {
  id: 11, pumpId: 3, duName: 'DU 1', duNumber: 1, operatorId: 9, operatorName: 'Ravi',
  nozzles: [{ id: 21, nozzleNumber: 1, fuelType: 'PETROL' }, { id: 22, nozzleNumber: 2, fuelType: 'DIESEL' }],
} as unknown as Shift

const operations: ShiftOperations = {
  operators: [{ operatorId: 9, operatorName: 'Ravi', role: 'SHIFT_OPERATOR', joinedAt: '2026-03-15T00:30:00Z', leftAt: null,
    active: true, onBreak: false, nozzleIds: [21, 22] }],
  reliefs: [],
  operatorShares: [],
  timeline: [{ at: '2026-03-15T00:30:00Z', type: 'SHIFT_OPENED', operatorName: 'Ravi', otherOperatorName: null, role: null,
    readings: [], amount: null, note: null }],
}

const operator = (id: number, fullName: string) => ({ id, fullName, role: 'OPERATOR' }) as StaffMember

describe('ShiftOperationsModal', () => {
  beforeEach(() => {
    vi.mocked(shiftApi.getOperations).mockResolvedValue(operations)
    vi.mocked(shiftApi.swapNozzle).mockResolvedValue(shift)
    vi.mocked(shiftApi.startRelief).mockResolvedValue(shift)
    vi.mocked(userApi.getOperators).mockResolvedValue([operator(9, 'Ravi'), operator(12, 'Sita')])
  })

  it('lets a manager move a nozzle to another operator at a meter reading', async () => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Mgr', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    const user = userEvent.setup()
    renderWithProviders(<ShiftOperationsModal shift={shift} onClose={vi.fn()} />)

    expect(await screen.findByText('Nozzles #1, #2')).toBeInTheDocument()
    expect(screen.getByText('Shift opened for Ravi')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Nozzle'), '22')
    await user.selectOptions(screen.getByLabelText('Move to'), '12')
    await user.type(screen.getByLabelText('Meter reading now'), '2005')
    await user.click(screen.getByRole('button', { name: 'Move nozzle' }))

    expect(shiftApi.swapNozzle).toHaveBeenCalledWith(3, 11, { nozzleId: 22, toOperatorId: 12, meterReading: 2005, note: undefined })
  })

  it('lets an operator start only their own break, with a reading for each of their nozzles', async () => {
    useAuthStore.setState({ user: { userId: 9, fullName: 'Ravi', role: 'OPERATOR', assignedPumpId: 3 }, isAuthenticated: true })
    vi.mocked(shiftApi.getOperations).mockResolvedValue({
      ...operations,
      operators: [...operations.operators, { operatorId: 12, operatorName: 'Sita', role: 'ADDITIONAL', joinedAt: '2026-03-15T01:00:00Z',
        leftAt: null, active: true, onBreak: false, nozzleIds: [] }],
    })
    const user = userEvent.setup()
    renderWithProviders(<ShiftOperationsModal shift={shift} onClose={vi.fn()} />)

    await screen.findByText('Nozzles #1, #2')
    expect(screen.queryByRole('button', { name: 'Move nozzle' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Operator going on break')).toBeDisabled()
    expect(userApi.getOperators).not.toHaveBeenCalled()
    await user.selectOptions(screen.getByLabelText('Relief operator'), '12')
    const start = screen.getByRole('button', { name: 'Start break' })
    expect(start).toBeDisabled()
    const readings = screen.getAllByRole('spinbutton').slice(0, 2)
    await user.type(readings[0], '1003')
    await user.type(readings[1], '2002')
    await user.click(start)

    expect(shiftApi.startRelief).toHaveBeenCalledWith(3, 11, expect.objectContaining({
      relievedOperatorId: 9, reliefOperatorId: 12,
      readings: [{ nozzleId: 21, meterReading: 1003 }, { nozzleId: 22, meterReading: 2002 }],
    }))
  })
})
