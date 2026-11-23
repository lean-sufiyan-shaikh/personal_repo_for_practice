import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react'
import { AxiosError } from 'axios'
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

    await user.click(search)
    fireEvent.mouseDown(await screen.findByRole('option', { name: /Fleet Co/ }))
    await user.click(screen.getByRole('button', { name: 'Save Entry' }))

    expect(shiftApi.addCreditEntry).toHaveBeenCalledWith(3, 11, expect.objectContaining({ clientId: 40, clientName: 'Fleet Co', amount: 250 }), expect.any(String))
    await waitFor(() => expect(useToastStore.getState().toasts).toEqual([
      expect.objectContaining({ message: 'Credit of ₹250.00 added for Fleet Co', variant: 'success' }),
    ]))
  })

  it('keeps the entry on this device, restores it on reopen and resends with the same key after a timeout', async () => {
    vi.mocked(shiftApi.addCreditEntry).mockRejectedValueOnce(new AxiosError('timeout', 'ECONNABORTED'))
    const first = userEvent.setup()
    renderWithProviders(<AddCreditEntryModal shift={shift} onClose={vi.fn()} />)
    await first.type(screen.getByPlaceholderText('Search client name…'), 'Fleet')
    fireEvent.mouseDown(await screen.findByRole('button', { name: /Fleet Co/ }))
    await first.type(screen.getByLabelText('Credit amount in rupees'), '250')
    await first.type(screen.getByLabelText('Bill No'), 'B-9')
    await first.click(screen.getByRole('button', { name: 'Save Entry' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Server didn't respond")
    const firstKey = vi.mocked(shiftApi.addCreditEntry).mock.calls[0][3]

    cleanup()
    const user = userEvent.setup()
    renderWithProviders(<AddCreditEntryModal shift={shift} onClose={vi.fn()} />)
    expect(screen.getByText(/^Resumed your unsaved entries from/)).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Search client name…')).toHaveValue('Fleet Co')
    expect(screen.getByLabelText('Credit amount in rupees')).toHaveValue(250)
    expect(screen.getByLabelText('Bill No')).toHaveValue('B-9')

    await user.click(screen.getByRole('button', { name: 'Save Entry' }))
    await waitFor(() => expect(shiftApi.addCreditEntry).toHaveBeenCalledTimes(2))
    expect(vi.mocked(shiftApi.addCreditEntry).mock.calls[1][3]).toBe(firstKey)
    await waitFor(() => expect(localStorage.getItem('ppms_draft:credit-entry:11')).toBeNull())
  })

  it('discards restored entries back to an empty form', async () => {
    localStorage.setItem('ppms_draft:credit-entry:11', JSON.stringify({
      savedAt: Date.now(),
      values: {
        clientSearch: 'Fleet Co', clientName: 'Fleet Co', clientId: 40, subAccountId: null, fuelType: 'PETROL',
        amount: '300', liters: '', inputMode: 'amount', billNo: '', description: '', submitAttempt: null,
      },
    }))
    const user = userEvent.setup()
    renderWithProviders(<AddCreditEntryModal shift={shift} onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Discard' }))

    expect(screen.getByPlaceholderText('Search client name…')).toHaveValue('')
    expect(screen.getByLabelText('Credit amount in rupees')).toHaveValue(null)
    expect(screen.getByRole('button', { name: 'Save Entry' })).toBeDisabled()
    expect(localStorage.getItem('ppms_draft:credit-entry:11')).toBeNull()
  })
})
