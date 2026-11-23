import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import { shiftApi } from '../../api/shiftApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import { userApi } from '../../api/userApi'
import type { StaffMember } from '../../api/userApi'
import { useToastStore } from '../../store/toastStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { DUOption, NozzleDetail, Shift } from '../../types/shift'
import OpenShiftModal from './OpenShiftModal'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { getDUs: vi.fn() } }))
vi.mock('../../api/shiftApi', () => ({ shiftApi: { openShift: vi.fn() } }))
vi.mock('../../api/shiftDefinitionApi', () => ({ shiftDefinitionApi: { getActive: vi.fn() } }))
vi.mock('../../api/userApi', () => ({ userApi: { getOperators: vi.fn() } }))

function nozzle(id: number, nozzleNumber: number, fuelType: NozzleDetail['fuelType']): NozzleDetail {
  return { id, nozzleNumber, fuelType, lastReading: 1000, maxMeterValue: 999_999, tankId: 1, status: 'ACTIVE' }
}

function du(id: number, nozzles: NozzleDetail[]): DUOption {
  return { id, pumpId: 3, duNumber: id, name: `DU ${id}`, status: 'ACTIVE', nozzles }
}

const operator: StaffMember = {
  id: 9, employeeId: 'E9', fullName: 'Ravi', phoneNumber: null, gender: null, nightShiftConsent: false, role: 'OPERATOR',
  assignedPumpId: 3, status: 'ACTIVE', dailyRate: null, shift1HourlyRate: null, standardHourlyRate: null,
}

const busyShift = { id: 70, operatorId: 50, nozzles: [{ id: 23, nozzleNumber: 3, fuelType: 'DIESEL' }] } as unknown as Shift

function nozzleGroup() {
  return screen.getByRole('group', { name: 'Select nozzles' })
}

describe('OpenShiftModal', () => {
  beforeEach(() => {
    vi.mocked(userApi.getOperators).mockResolvedValue([operator])
    vi.mocked(shiftDefinitionApi.getActive).mockResolvedValue([])
    vi.mocked(shiftApi.openShift).mockResolvedValue({ operatorName: 'Ravi' } as Shift)
    useToastStore.setState({ toasts: [] })
  })

  it('picks the only dispensary unit and its free nozzles, then says what is still missing', async () => {
    vi.mocked(pumpApi.getDUs).mockResolvedValue([
      du(1, [nozzle(21, 1, 'PETROL'), nozzle(22, 2, 'DIESEL'), nozzle(23, 3, 'DIESEL')]),
    ])
    const user = userEvent.setup()
    renderWithProviders(<OpenShiftModal pumpId={3} activeShifts={[busyShift]} onClose={vi.fn()} />)

    expect(await screen.findByText('2 selected')).toBeInTheDocument()
    expect(within(nozzleGroup()).getByRole('button', { name: /#3/ })).toHaveTextContent('In use')
    expect(screen.getByRole('button', { name: 'Pick an operator' })).toBeDisabled()

    await user.click(await screen.findByRole('button', { name: /Ravi/ }))
    expect(screen.getByRole('button', { name: 'Confirm the meter readings' })).toBeDisabled()

    await user.click(screen.getByLabelText(/I confirm the meter readings above are correct/))
    await user.click(screen.getByRole('button', { name: 'Open Shift' }))

    expect(shiftApi.openShift).toHaveBeenCalledWith(3, { duId: 1, nozzleIds: [21, 22], operatorId: 9 })
    await waitFor(() => expect(useToastStore.getState().toasts).toEqual([
      expect.objectContaining({ message: 'Shift opened for Ravi', variant: 'success' }),
    ]))
  })

  it('leaves the choice to the user when the pump has several dispensary units', async () => {
    vi.mocked(pumpApi.getDUs).mockResolvedValue([du(1, [nozzle(21, 1, 'PETROL')]), du(2, [nozzle(31, 1, 'DIESEL')])])
    const user = userEvent.setup()
    renderWithProviders(<OpenShiftModal pumpId={3} activeShifts={[]} onClose={vi.fn()} />)

    const secondDu = await screen.findByRole('button', { name: /DU 2/ })
    expect(screen.getByRole('button', { name: 'Pick a dispensary unit' })).toBeDisabled()
    expect(screen.queryByRole('group', { name: 'Select nozzles' })).not.toBeInTheDocument()

    await user.click(secondDu)
    expect(screen.getByRole('button', { name: 'Select at least one nozzle' })).toBeDisabled()
  })
})
