import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { inventoryApi } from '../../api/inventoryApi'
import type { TankStock } from '../../api/inventoryApi'
import { userApi } from '../../api/userApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { DipCheckModal } from './DipCheckModal'

vi.mock('../../api/inventoryApi', () => ({
  inventoryApi: { getOpenShiftNozzles: vi.fn(), recordDipCheck: vi.fn() },
}))
vi.mock('../../api/userApi', () => ({ userApi: { getStaff: vi.fn() } }))

const tank: TankStock = {
  tankId: 1, tankIdentifier: 'T1', fuelType: 'PETROL', capacity: 10_000, currentStock: 1_000,
  stockPercentage: 10, lowStock: true, dipTolerance: 50, pumpId: 6,
}

describe('DipCheckModal', () => {
  beforeEach(() => {
    vi.mocked(userApi.getStaff).mockResolvedValue([])
    vi.mocked(inventoryApi.recordDipCheck).mockResolvedValue({} as never)
  })

  it('asks for the meter reading of each nozzle on an open shift and takes its sales off the expected stock', async () => {
    vi.mocked(inventoryApi.getOpenShiftNozzles).mockResolvedValue([{
      shiftId: 9, nozzleId: 21, nozzleNumber: 1, duName: 'DU-1', fuelType: 'PETROL',
      openingReading: 1000, maxMeterValue: 99_999_999.999,
    }])
    const user = userEvent.setup()
    renderWithProviders(<DipCheckModal pumpId={6} tank={tank} onClose={vi.fn()} />)

    const reading = await screen.findByLabelText('Nozzle #1 · DU-1 meter reading')
    expect(reading).toHaveAccessibleDescription('Opened at 1,000.000')
    await user.type(reading, '1030')
    await user.type(screen.getByLabelText('Dipstick Reading (Litres)'), '965')

    expect(screen.getByText('-5.0 L variance')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save DIP Check' }))
    expect(inventoryApi.recordDipCheck).toHaveBeenCalledWith(6, {
      tankId: 1, measuredQuantity: 965, checkedByUserId: undefined, notes: undefined,
      nozzleReadings: [{ nozzleId: 21, meterReading: 1030 }],
    })
  })

  it('has no date to choose, because a dip check is always for today', async () => {
    vi.mocked(inventoryApi.getOpenShiftNozzles).mockResolvedValue([])
    renderWithProviders(<DipCheckModal pumpId={6} tank={tank} onClose={vi.fn()} />)

    expect(await screen.findByText(/Recorded for today/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Check Date')).not.toBeInTheDocument()
    expect(screen.queryByText('Current meter readings')).not.toBeInTheDocument()
  })
})
