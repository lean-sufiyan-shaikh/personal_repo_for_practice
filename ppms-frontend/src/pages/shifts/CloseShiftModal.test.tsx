import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import { shiftApi } from '../../api/shiftApi'
import { userApi } from '../../api/userApi'
import type { StaffMember } from '../../api/userApi'
import type { PumpSummary } from '../../api/pumpApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Shift } from '../../types/shift'
import CloseShiftModal from './CloseShiftModal'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCreditClients: vi.fn(), getMyPumps: vi.fn() } }))
vi.mock('../../api/shiftApi', () => ({
  shiftApi: { closeShift: vi.fn(), createHandover: vi.fn(), voidCreditEntry: vi.fn(), getShift: vi.fn() },
}))
vi.mock('../../api/userApi', () => ({ userApi: { getOperators: vi.fn() } }))

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

function operator(id: number, fullName: string): StaffMember {
  return {
    id, employeeId: `E${id}`, fullName, phoneNumber: null, gender: null, nightShiftConsent: false, role: 'OPERATOR',
    assignedPumpId: 3, status: 'ACTIVE', dailyRate: null, shift1HourlyRate: null, standardHourlyRate: null,
  }
}

async function fillShift(cash: string) {
  const user = userEvent.setup()
  renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
  await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
  await user.type(screen.getByLabelText('Cash (₹)'), cash)
  return user
}

describe('CloseShiftModal', () => {
  beforeEach(() => {
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([])
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([{ id: 3, discrepancyEscalationThreshold: 50 } as PumpSummary])
    vi.mocked(shiftApi.closeShift).mockResolvedValue({} as never)
    vi.mocked(shiftApi.getShift).mockResolvedValue(shift)
    vi.mocked(shiftApi.createHandover).mockResolvedValue({} as never)
    vi.mocked(userApi.getOperators).mockResolvedValue([operator(9, 'Ravi'), operator(12, 'Sita'), operator(13, 'Busy Bala')])
  })

  it('puts the cursor in the first end reading rather than the No change box', () => {
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    expect(screen.getByPlaceholderText('Enter end reading')).toHaveFocus()
  })

  it('fills cash with what is still due after UPI, card, fleet card and credit', async () => {
    const user = userEvent.setup()
    const recordedCredit = {
      id: 42, clientId: 3, clientName: 'Fleet Co', billNo: null, amount: 100, fuelType: 'PETROL',
      description: null, voidStatus: 'ACTIVE' as const, voidReason: null,
    }
    vi.mocked(shiftApi.getShift).mockResolvedValue({ ...shift, creditEntries: [recordedCredit] })
    renderWithProviders(<CloseShiftModal shift={{ ...shift, creditEntries: [recordedCredit] }} onClose={vi.fn()} />)

    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    await user.type(screen.getByLabelText('UPI (₹)'), '300')
    await user.type(screen.getByLabelText('Card (₹)'), '50.50')
    await user.type(screen.getByLabelText('Fleet Card (₹)'), '20')
    await user.click(screen.getByRole('button', { name: 'Rest in cash · ₹529.50' }))

    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(529.5)
    expect(within(screen.getByRole('dialog')).getByText('Balanced')).toBeInTheDocument()
  })

  it('fills cash with zero, never a negative amount, when more than is due came in by UPI', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)

    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    await user.type(screen.getByLabelText('UPI (₹)'), '1200')
    await user.click(screen.getByRole('button', { name: 'Rest in cash · ₹0.00' }))

    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(0)
  })

  it('lists cash first among the payments', () => {
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    const paymentNames = screen.getAllByPlaceholderText('Enter amount').map((input) => input.closest('label')?.textContent)
    expect(paymentNames).toEqual(['Cash (₹)', 'UPI (₹)', 'Card (₹)', 'Fleet Card (₹)'])
  })

  it('hands the shift over by closing it with the entered readings and payments for the chosen operator', async () => {
    const user = userEvent.setup()
    const busyShift = { ...shift, id: 99, operatorId: 13 }
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} handoverActiveShifts={[shift, busyShift]} />)

    expect(screen.getByRole('dialog', { name: 'Hand Over Shift' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /Sita/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Ravi/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Busy Bala/ })).not.toBeInTheDocument()
    expect(screen.getByText('Choose Incoming Operator')).toBeInTheDocument()
    expect(screen.queryByText('Assign Operator')).not.toBeInTheDocument()

    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    await user.type(screen.getByLabelText('Cash (₹)'), '1000')
    expect(screen.getByRole('button', { name: 'Choose the incoming operator to continue' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: /Sita/ }))
    await user.click(screen.getByLabelText('Cash counted with the incoming operator'))
    await user.type(screen.getByLabelText('Handover notes (optional)'), 'Lunch break')
    await user.click(screen.getByRole('button', { name: 'Review handover — Balanced' }))
    expect(screen.getByRole('heading', { name: 'Check before handing over' })).toHaveFocus()
    expect(within(screen.getByText('Incoming operator').parentElement as HTMLElement).getByText('Sita')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm handover' }))

    expect(shiftApi.closeShift).not.toHaveBeenCalled()
    expect(shiftApi.createHandover).toHaveBeenCalledWith(3, {
      outgoingShiftId: 11,
      incomingOperatorId: 12,
      physicalCashVerified: true,
      meterReadingsVerified: false,
      notes: 'Lunch break',
      closing: expect.objectContaining({
        fuelReadings: [{ nozzleId: 21, endReading: 1010 }],
        cashCollected: 1000,
      }),
    }, expect.any(String))
    expect(await screen.findByRole('heading', { name: 'Shift handed over' })).toBeInTheDocument()
    expect(screen.getByText('A new shift is open for Sita on the same nozzles.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open next shift' })).not.toBeInTheDocument()
  })

  it('shows a credit entry added while the modal is open and the reason a close is refused', async () => {
    const user = userEvent.setup()
    const addedMeanwhile = {
      id: 42, clientId: 3, clientName: 'Fleet Co', billNo: null, amount: 250, fuelType: 'PETROL',
      description: null, voidStatus: 'ACTIVE' as const, voidReason: null,
    }
    vi.mocked(shiftApi.getShift).mockResolvedValue({ ...shift, creditEntries: [addedMeanwhile] })
    vi.mocked(shiftApi.closeShift).mockRejectedValue(new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 400, statusText: 'Bad Request', headers: {}, config: { headers: new AxiosHeaders() },
      data: { message: 'Sum of all credit entries does not match the credit total.' },
    }))
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)

    expect(await screen.findByText('Fleet Co')).toBeInTheDocument()
    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    await user.type(screen.getByLabelText('Cash (₹)'), '750')
    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({ creditTotal: 250 }), expect.any(String))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sum of all credit entries does not match the credit total.')
  })

  it('asks for readings and payment before it can close', () => {
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Close Shift' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Fill in readings & payment to continue' })).toBeDisabled()
  })

  it('keeps a neutral header until readings and payment are entered, then turns red for a shortage', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    const header = screen.getByRole('heading', { name: 'Close Shift' }).closest('.ui-modal-header')
    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    expect(header).toHaveClass('ui-modal-header--neutral')

    await user.type(screen.getByLabelText('Cash (₹)'), '900')
    expect(header).toHaveClass('ui-modal-header--danger')
  })

  it('asks inside the app before discarding entered details', async () => {
    const onClose = vi.fn()
    const browserConfirm = vi.spyOn(window, 'confirm')
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={onClose} />)
    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('dialog', { name: 'Discard what you entered?' })).toBeInTheDocument()
    expect(browserConfirm).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect(onClose).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('shows Balanced and closes the shift when collection matches the meter', async () => {
    const user = await fillShift('1000')
    expect(screen.getByText('Balanced')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({
      fuelReadings: [{ nozzleId: 21, endReading: 1010 }],
      cashCollected: 1000,
      discrepancyReason: undefined,
    }), expect.any(String))
    expect(await screen.findByRole('heading', { name: 'Shift closed' })).toBeInTheDocument()
  })

  it('shows Short and requires a reason, with no round off for a gap of a rupee or more', async () => {
    const user = await fillShift('900')
    expect(screen.getByText('SHORT ₹100.00')).toBeInTheDocument()
    expect(screen.getByText('Shift is SHORT by ₹100.00')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Round off/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add a reason above to close' })).toBeDisabled()

    await user.type(screen.getByRole('textbox'), 'Customer paid later')
    await user.click(screen.getByRole('button', { name: 'Review close with discrepancy' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({ discrepancyReason: 'Customer paid later' }), expect.any(String))
  })

  it('rounds a gap under one rupee into cash so the shift balances', async () => {
    const user = await fillShift('999.40')
    expect(screen.getByText('Shift is SHORT by ₹0.60')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Round off ₹0.60 into cash' }))
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(1000)
    expect(within(screen.getByRole('dialog')).getByText('Balanced')).toBeInTheDocument()
  })

  it('shows Over when more was collected than due', async () => {
    await fillShift('1000.50')
    expect(screen.getByText('OVER ₹0.50')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Round off ₹0.50 into cash' })).toBeInTheDocument()
  })

  it('corrects a pre-recorded credit entry by closing, without voiding it first', async () => {
    const user = userEvent.setup()
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([{
      id: 3, pumpId: 3, name: 'Acme', phone: null, notes: null, createdAt: '2026-01-01T00:00:00Z',
      parentClientId: null, parentClientName: null, isParent: false, isActive: true, hasPortalPin: false,
    }])
    const recordedEntry = {
      id: 41, clientId: 3, clientName: 'Acme', billNo: null, amount: 200, fuelType: 'PETROL',
      description: null, voidStatus: 'ACTIVE' as const, voidReason: null,
    }
    vi.mocked(shiftApi.getShift).mockResolvedValue({ ...shift, creditEntries: [recordedEntry] })
    renderWithProviders(<CloseShiftModal shift={{ ...shift, creditEntries: [recordedEntry] }} onClose={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    await user.type(screen.getByLabelText('Cash (₹)'), '800')
    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    expect(shiftApi.voidCreditEntry).not.toHaveBeenCalled()
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({
      creditTotal: 200,
      replacedCreditEntryIds: [41],
      creditEntries: [expect.objectContaining({ clientId: 3, amount: 200 })],
    }), expect.any(String))
  })

  it('reviews every figure before closing and goes back to the form on Edit', async () => {
    const user = await fillShift('900')
    await user.type(screen.getByRole('textbox'), 'Customer paid later')
    await user.click(screen.getByRole('button', { name: 'Review close with discrepancy' }))

    const review = screen.getByRole('dialog', { name: 'Check before closing' })
    expect(screen.getByRole('heading', { name: 'Check before closing' })).toHaveFocus()
    expect(within(review).getByRole('row', { name: /Nozzle #1 · Petrol/ })).toHaveTextContent('10.000 L₹1,000.00')
    expect(within(review).getByRole('row', { name: /Total due/ })).toHaveTextContent('₹1,000.00')
    expect(within(review).getByText('Cash').nextSibling).toHaveTextContent('₹900.00')
    expect(within(review).getByText('Credit (0 entries)')).toBeInTheDocument()
    expect(within(review).getByText('SHORT ₹100.00')).toBeInTheDocument()
    expect(within(review).getByText('Reason: Customer paid later')).toBeInTheDocument()
    expect(await within(review).findByText(/more than the ₹50.00 limit/)).toBeInTheDocument()
    expect(review.querySelector('.ui-modal-backdrop')).toHaveClass('ui-modal-backdrop--fullscreen')
    expect(within(review).getByRole('button', { name: 'Confirm close' }).parentElement).toHaveClass('ui-modal-footer--large')

    await user.click(within(review).getByRole('button', { name: 'Edit' }))
    expect(shiftApi.closeShift).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(900)
    expect(screen.getByRole('dialog', { name: 'Close Shift' }).querySelector('.ui-modal-backdrop'))
      .not.toHaveClass('ui-modal-backdrop--fullscreen')
  })

  it('after closing shows one summary instead of a toast, and offers the next shift, print and done', async () => {
    const onClose = vi.fn()
    const onOpenNextShift = vi.fn()
    const closed = { ...shift, status: 'CLOSED_BALANCED' as const, actualEndTime: '2026-03-15T08:30:00Z', totalAmountDue: 1000 }
    vi.mocked(shiftApi.closeShift).mockResolvedValue(closed)
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={onClose} onOpenNextShift={onOpenNextShift} />)
    await user.type(screen.getByPlaceholderText('Enter end reading'), '1010')
    await user.type(screen.getByLabelText('Cash (₹)'), '1000')
    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    const summary = await screen.findByRole('dialog', { name: 'Shift closed' })
    expect(within(summary).getByText('Balanced')).toBeInTheDocument()
    expect(within(summary).getByText('Total due').nextSibling).toHaveTextContent('₹1,000.00')
    expect(within(summary).getByText('Credit entries').nextSibling).toHaveTextContent('0')

    await user.click(within(summary).getByRole('button', { name: 'Print / share' }))
    const report = await screen.findByRole('dialog', { name: 'Shift Handover Report' })
    await user.click(within(report).getByRole('button', { name: 'Close' }))

    await user.click(await screen.findByRole('button', { name: 'Open next shift' }))
    expect(onOpenNextShift).toHaveBeenCalledWith(5, [21])

    await user.click(screen.getByRole('button', { name: 'Done' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('reuses the same idempotency key when the close is resent after a timeout, even after a reload', async () => {
    const timeout = new AxiosError('timeout of 20000ms exceeded', 'ECONNABORTED')
    vi.mocked(shiftApi.closeShift).mockRejectedValueOnce(timeout).mockRejectedValueOnce(timeout)
    const first = await fillShift('1000')
    await first.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await first.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Server didn't respond")

    await first.click(screen.getByRole('button', { name: 'Confirm close' }))
    await waitFor(() => expect(shiftApi.closeShift).toHaveBeenCalledTimes(2))
    const firstKey = vi.mocked(shiftApi.closeShift).mock.calls[0][3]
    expect(firstKey).toMatch(/^[0-9a-f]{32}$/)
    expect(vi.mocked(shiftApi.closeShift).mock.calls[1][3]).toBe(firstKey)

    cleanup()
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    await waitFor(() => expect(shiftApi.closeShift).toHaveBeenCalledTimes(3))
    expect(vi.mocked(shiftApi.closeShift).mock.calls[2][3]).toBe(firstKey)
  })

  it('sends a new key once the figures change after a failed attempt', async () => {
    vi.mocked(shiftApi.closeShift).mockRejectedValueOnce(new AxiosError('timeout', 'ECONNABORTED'))
    const user = await fillShift('1000')
    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    await screen.findByRole('alert')

    await user.click(screen.getByRole('button', { name: 'Edit' }))
    await user.type(screen.getByLabelText('UPI (₹)'), '100')
    await user.clear(screen.getByLabelText('Cash (₹)'))
    await user.type(screen.getByLabelText('Cash (₹)'), '900')
    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    await waitFor(() => expect(shiftApi.closeShift).toHaveBeenCalledTimes(2))
    const [firstCall, secondCall] = vi.mocked(shiftApi.closeShift).mock.calls
    expect(secondCall[3]).not.toBe(firstCall[3])
  })
})

describe('CloseShiftModal drafts', () => {
  beforeEach(() => {
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([])
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([])
    vi.mocked(shiftApi.getShift).mockResolvedValue(shift)
    vi.mocked(userApi.getOperators).mockResolvedValue([operator(12, 'Sita')])
  })

  it('saves entries on this device shortly after typing, separately for close and handover', async () => {
    await fillShift('900')

    await waitFor(() => expect(localStorage.getItem('ppms_draft:close-shift:11')).not.toBeNull())
    const saved = JSON.parse(localStorage.getItem('ppms_draft:close-shift:11') as string)
    expect(saved.values).toMatchObject({ endReadings: { 21: '1010' }, cash: '900' })
    expect(localStorage.getItem('ppms_draft:handover:11')).toBeNull()
  })

  it('restores saved entries with a notice and clears them on Discard', async () => {
    localStorage.setItem('ppms_draft:close-shift:11', JSON.stringify({
      savedAt: Date.now() - 60_000,
      values: {
        endReadings: { 21: '1010' }, noChangeNozzleIds: [], cash: '700', upi: '', card: '', fleetCard: '',
        discrepancyReason: '',
        handoverDetails: { incomingOperatorId: null, physicalCashVerified: false, meterReadingsVerified: false, notes: '' },
        creditRows: [], submitAttempt: null,
      },
    }))
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)

    expect(screen.getByText(/^Resumed your unsaved entries from \d{2}:\d{2} (am|pm)$/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Enter end reading')).toHaveValue(1010)
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(700)

    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.queryByText(/Resumed your unsaved entries/)).not.toBeInTheDocument()
    expect(screen.getByPlaceholderText('Enter end reading')).toHaveValue(null)
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(null)
    expect(localStorage.getItem('ppms_draft:close-shift:11')).toBeNull()
  })

  it('clears the draft once the shift is closed', async () => {
    vi.mocked(shiftApi.closeShift).mockResolvedValue({ ...shift, status: 'CLOSED_BALANCED' })
    const user = await fillShift('1000')
    await waitFor(() => expect(localStorage.getItem('ppms_draft:close-shift:11')).not.toBeNull())

    await user.click(screen.getByRole('button', { name: 'Review close — Balanced' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    await screen.findByRole('heading', { name: 'Shift closed' })

    expect(localStorage.getItem('ppms_draft:close-shift:11')).toBeNull()
    await new Promise((resolve) => setTimeout(resolve, 500))
    expect(localStorage.getItem('ppms_draft:close-shift:11')).toBeNull()
  })

  it('still works when the browser refuses storage', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })

    await fillShift('1000')

    expect(screen.getByText('Balanced')).toBeInTheDocument()
    await waitFor(() => expect(warn).toHaveBeenCalledWith(expect.stringContaining('Could not save the draft'), expect.any(Error)))
    setItem.mockRestore()
    warn.mockRestore()
  })
})
