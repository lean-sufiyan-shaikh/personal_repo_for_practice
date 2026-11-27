import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shiftApi } from '../../../api/shiftApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { Shift } from '../../../types/shift'
import { AmendShiftModal } from './AmendShiftModal'
import { canAmendShift } from './amendFigures'

vi.mock('../../../api/shiftApi', () => ({ shiftApi: { amendShift: vi.fn() } }))

const closedShift: Shift = {
  id: 41, pumpId: 3, duId: 5, duNumber: 1, duName: 'DU 1',
  nozzles: [{ id: 21, nozzleNumber: 1, fuelType: 'PETROL' }],
  operatorId: 9, operatorName: 'Ravi', openedByUserName: 'Owner', shiftWindow: 'MORNING',
  shiftDate: '2026-11-20', actualStartTime: '2026-11-20T00:30:00Z', actualEndTime: '2026-11-20T08:30:00Z', scheduledEndTime: null,
  fuelReadings: [{ nozzleId: 21, fuelType: 'PETROL', startReading: 1000, endReading: 1100, priceSnapshot: 100, unitsSold: 100, maxMeterValue: 99999999 }],
  totalAmountDue: 10000, cashCollected: 9000, upiCollected: 500, cardCollected: 0, fleetCardCollected: 0,
  creditTotal: 0, cashDropped: 2000, cashDenominations: null, discrepancyAmount: 500, discrepancyType: 'SHORT', discrepancyReason: null,
  discrepancyResolution: null, discrepancyResolutionNote: null,
  investigationNote: null, investigatedByName: null, investigationStartedAt: null, cashRecoveryAmount: null,
  status: 'CLOSED_DISCREPANCY_PENDING', isBackfilled: false, creditEntries: [],
}

describe('AmendShiftModal', () => {
  beforeEach(() => {
    vi.mocked(shiftApi.amendShift).mockResolvedValue(closedShift)
  })

  it('prefills the closing figures and works out due, collected and the difference again as the user types', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AmendShiftModal shift={closedShift} onClose={vi.fn()} />)

    const reading = screen.getByLabelText('Closing reading — nozzle #1 (Petrol)')
    expect(reading).toHaveValue(1100)
    expect(screen.getByLabelText('Cash in the bag')).toHaveValue(7000)

    await user.clear(reading)
    await user.type(reading, '1095')
    const figures = screen.getByLabelText('Before and after')
    expect(within(figures).getByText('Amount due').nextElementSibling).toHaveTextContent('₹10,000.00→₹9,500.00')
    expect(within(figures).getByText('Litres sold').nextElementSibling).toHaveTextContent('100.00 L→95.00 L')
    expect(within(figures).getByText('Difference (collected − due)').nextElementSibling).toHaveTextContent('−₹500.00→₹0.00')
  })

  it('refuses to send without a reason, then sends the corrected figures', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderWithProviders(<AmendShiftModal shift={closedShift} onClose={onClose} />)

    const upi = screen.getByLabelText('UPI')
    await user.clear(upi)
    await user.type(upi, '1000')
    await user.click(screen.getByRole('button', { name: 'Save amendment' }))
    expect(screen.getByText('Give a reason for the amendment.')).toBeInTheDocument()
    expect(shiftApi.amendShift).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('Why the shift is amended'), 'UPI slip found')
    await user.click(screen.getByRole('button', { name: 'Save amendment' }))
    expect(shiftApi.amendShift).toHaveBeenCalledWith(3, 41, {
      fuelReadings: [{ nozzleId: 21, endReading: 1100 }],
      cashCollected: 7000, upiCollected: 1000, cardCollected: 0, fleetCardCollected: 0, reason: 'UPI slip found',
    })
    await vi.waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('shows the server refusal', async () => {
    const user = userEvent.setup()
    vi.mocked(shiftApi.amendShift).mockRejectedValue({
      isAxiosError: true, response: { status: 422, data: { code: 'SHIFT_AMEND_NO_CHANGE', message: 'The amended figures are the same as the shift\'s current figures.' } },
    })
    renderWithProviders(<AmendShiftModal shift={closedShift} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Why the shift is amended'), 'Check')
    await user.click(screen.getByRole('button', { name: 'Save amendment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('same as the shift\'s current figures')
  })
})

describe('canAmendShift', () => {
  it('lets only an owner or admin amend a closed shift whose discrepancy was not settled with money', () => {
    expect(canAmendShift(closedShift, 'OWNER')).toBe(true)
    expect(canAmendShift(closedShift, 'ADMIN')).toBe(true)
    expect(canAmendShift(closedShift, 'MANAGER')).toBe(false)
    expect(canAmendShift({ ...closedShift, status: 'OPEN' }, 'OWNER')).toBe(false)
    expect(canAmendShift({ ...closedShift, discrepancyResolution: 'CASH_RECOVERY' }, 'OWNER')).toBe(false)
    expect(canAmendShift({ ...closedShift, discrepancyResolution: 'WAIVED', status: 'CLOSED_DISCREPANCY_RESOLVED' }, 'ADMIN')).toBe(true)
  })
})
