import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../../api/pumpApi'
import type { CreditClient } from '../../../api/pumpApi'
import { shiftApi } from '../../../api/shiftApi'
import { useAuthStore } from '../../../store/authStore'
import { useToastStore } from '../../../store/toastStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { Shift } from '../../../types/shift'
import { AddCreditEntryModal } from './AddCreditEntryModal'

vi.mock('../../../api/pumpApi', () => ({ pumpApi: { getCreditClients: vi.fn() } }))
vi.mock('../../../api/shiftApi', () => ({ shiftApi: { addCreditEntry: vi.fn(), voidCreditEntry: vi.fn() } }))

const shift: Shift = {
  id: 11, pumpId: 3, duId: 5, duNumber: 1, duName: 'DU 1',
  nozzles: [{ id: 21, nozzleNumber: 1, fuelType: 'PETROL' }],
  operatorId: 9, operatorName: 'Ravi', openedByUserName: 'Owner', shiftWindow: 'MORNING',
  shiftDate: '2026-03-15', actualStartTime: '2026-03-15T00:30:00Z', actualEndTime: null, scheduledEndTime: null,
  fuelReadings: [{
    nozzleId: 21, fuelType: 'PETROL', startReading: 1000, endReading: null,
    priceSnapshot: 100, unitsSold: null, maxMeterValue: 999_999,
  }],
  totalAmountDue: null, cashCollected: null, upiCollected: null, cardCollected: null, fleetCardCollected: null,
  creditTotal: null, discrepancyAmount: null, discrepancyType: null, discrepancyReason: null,
  discrepancyResolution: null, discrepancyResolutionNote: null,
  investigationNote: null, investigatedByName: null, investigationStartedAt: null, cashRecoveryAmount: null,
  status: 'OPEN', isBackfilled: false, creditEntries: [],
}

const fleetClient: CreditClient = {
  id: 40, pumpId: 3, name: 'Fleet Co', phone: null, notes: null, createdAt: '2026-01-01T00:00:00Z',
  parentClientId: null, parentClientName: null, isParent: false, isActive: true, hasPortalPin: false,
} as CreditClient

describe('AddCreditEntryModal', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 9, fullName: 'Ravi', role: 'OPERATOR', assignedPumpId: 3 }, isAuthenticated: true })
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([fleetClient])
    vi.mocked(shiftApi.addCreditEntry).mockResolvedValue({} as never)
    useToastStore.setState({ toasts: [] })
  })

  it('cannot save credit for a typed name that is not a client and saves once a client is picked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AddCreditEntryModal shift={shift} onClose={vi.fn()} />)

    const search = screen.getByPlaceholderText('Search client name…')
    await user.type(search, 'Fleet')
    await user.type(screen.getByLabelText('Credit amount in rupees'), '250')
    expect(screen.getByRole('button', { name: 'Save Entry' })).toBeDisabled()

    fireEvent.mouseDown(await screen.findByRole('button', { name: /Fleet Co/ }))
    await user.click(screen.getByRole('button', { name: 'Save Entry' }))

    expect(shiftApi.addCreditEntry).toHaveBeenCalledWith(3, 11, expect.objectContaining({ clientId: 40, clientName: 'Fleet Co', amount: 250 }))
    await waitFor(() => expect(useToastStore.getState().toasts).toEqual([
      expect.objectContaining({ message: 'Credit of ₹250.00 added for Fleet Co', variant: 'success' }),
    ]))
  })
})
