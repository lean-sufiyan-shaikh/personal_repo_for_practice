import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import { shiftApi } from '../../api/shiftApi'
import { userApi } from '../../api/userApi'
import { cashApi } from '../../api/cashApi'
import type { PumpSummary } from '../../api/pumpApi'
import { useAuthStore } from '../../store/authStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import CloseShiftModal from './CloseShiftModal'
import { cashTotalField, drop, endReading, fillShift, operator, shift } from './closeShiftModalTestData'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCreditClients: vi.fn(), getMyPumps: vi.fn() } }))
vi.mock('../../api/shiftApi', () => ({
  shiftApi: { closeShift: vi.fn(), createHandover: vi.fn(), voidCreditEntry: vi.fn(), getShift: vi.fn(), recordBlindCount: vi.fn() },
}))
vi.mock('../../api/userApi', () => ({ userApi: { getOperators: vi.fn() } }))
vi.mock('../../api/cashApi', () => ({ cashApi: { getShiftDrops: vi.fn(), confirmDrop: vi.fn(), rejectDrop: vi.fn() } }))

function blockedCreditRefusal() {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 400, statusText: 'Bad Request', headers: {}, config: { headers: new AxiosHeaders() },
    data: { code: 'CREDIT_CLOSE_BLOCKED_CLIENTS', message: 'Blocked',
      params: { overLimitClients: 'Fleet Co', onHoldClients: '—', overdueClients: 'Late Co' } },
  })
}

describe('CloseShiftModal', () => {
  beforeEach(() => {
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([])
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([{ id: 3, discrepancyEscalationThreshold: 50 } as PumpSummary])
    vi.mocked(shiftApi.closeShift).mockResolvedValue({} as never)
    vi.mocked(shiftApi.getShift).mockResolvedValue(shift)
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([])
    vi.mocked(shiftApi.createHandover).mockResolvedValue({} as never)
    vi.mocked(userApi.getOperators).mockResolvedValue([operator(9, 'Ravi'), operator(12, 'Sita'), operator(13, 'Busy Bala')])
  })

  it('puts the cursor in the first end reading rather than the No change box', () => {
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    expect(endReading()).toHaveFocus()
  })

  it('fills cash with what is still due after UPI, card, fleet card and credit', async () => {
    const user = userEvent.setup()
    const recordedCredit = {
      id: 42, clientId: 3, clientName: 'Fleet Co', billNo: null, amount: 100, fuelType: 'PETROL',
      description: null, voidStatus: 'ACTIVE' as const, voidReason: null,
    }
    vi.mocked(shiftApi.getShift).mockResolvedValue({ ...shift, creditEntries: [recordedCredit] })
    renderWithProviders(<CloseShiftModal shift={{ ...shift, creditEntries: [recordedCredit] }} onClose={vi.fn()} />)

    await user.click(endReading())
    await user.paste('1010')
    await user.click(screen.getByLabelText('UPI (₹)'))
    await user.paste('300')
    await user.click(screen.getByLabelText('Card (₹)'))
    await user.paste('50.50')
    await user.click(screen.getByLabelText('Fleet card (₹)'))
    await user.paste('20')
    await cashTotalField(user)
    await user.click(screen.getByRole('button', { name: 'Fill rest · ₹529.50' }))

    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(529.5)
    expect(within(screen.getByRole('dialog')).getByText('Balanced')).toBeInTheDocument()
  })

  it('offers Fill rest only while something is left to collect in cash, and hides it once cash holds the rest', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()

    await user.click(endReading())
    await user.paste('1010')
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()
    await cashTotalField(user)
    expect(screen.getByRole('button', { name: 'Fill rest · ₹1,000.00' })).toBeInTheDocument()

    await user.click(screen.getByLabelText('UPI (₹)'))
    await user.paste('1200')
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()

    await user.clear(screen.getByLabelText('UPI (₹)'))
    await user.click(screen.getByRole('button', { name: 'Fill rest · ₹1,000.00' }))
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(1000)
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()
  })

  it('lists cash first among the payments', async () => {
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await cashTotalField(userEvent.setup())
    const payments = screen.getByRole('region', { name: 'Payments' })
    const paymentNames = within(payments).getAllByRole('spinbutton').map((input) => (input as HTMLInputElement).labels?.[0]?.textContent)
    expect(paymentNames).toEqual(['Cash (₹)', 'UPI (₹)', 'Card (₹)', 'Fleet card (₹)'])
  })

  it('shows the sale of each nozzle as the end reading is typed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    expect(screen.getByText('Start 1,000 L · ₹100.00/L')).toBeInTheDocument()

    await user.click(endReading())
    await user.paste('1012.5')
    expect(screen.getByText(/= 12\.50 L × ₹100\.00 =/)).toHaveTextContent('= 12.50 L × ₹100.00 = ₹1,250.00')
    expect(endReading()).toHaveAccessibleDescription('= 12.50 L × ₹100.00 = ₹1,250.00')
  })

  it('explains an end reading below the start instead of a sale line', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(endReading())
    await user.paste('990')

    expect(screen.getByRole('alert')).toHaveTextContent('End reading cannot be less than start (1000)')
    expect(endReading()).toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByText(/× ₹100\.00/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review close' })).toHaveAccessibleDescription(/Fix end readings below the start/)
  })

  it('marks a nozzle unchanged with the light No change box', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(screen.getByRole('checkbox', { name: 'No change' }))

    expect(endReading()).toBeDisabled()
    expect(endReading()).toHaveValue(1000)
    expect(screen.getByText('No change · 0 L sold')).toBeInTheDocument()
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

    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('1000')
    const review = screen.getByRole('button', { name: 'Review handover' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed Choose the incoming operator')

    await user.click(screen.getByRole('button', { name: /Sita/ }))
    await user.click(screen.getByLabelText('Cash counted with the incoming operator'))
    await user.click(screen.getByLabelText('Handover notes (optional)'))
    await user.paste('Lunch break')
    await user.click(screen.getByRole('button', { name: 'Review handover' }))
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
    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('750')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({ creditTotal: 250 }), expect.any(String))
    expect(await screen.findByRole('alert')).toHaveTextContent('Sum of all credit entries does not match the credit total.')
  })

  it('lets an owner close with blocked credit after giving a reason, resending with the override', async () => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    vi.mocked(shiftApi.closeShift).mockRejectedValueOnce(blockedCreditRefusal()).mockResolvedValueOnce({} as never)
    const user = await fillShift('1000')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    const blocked = await screen.findByRole('alert', { name: 'Credit for these clients is blocked' })
    expect(blocked).toHaveTextContent('Over limit: Fleet Co')
    expect(blocked).toHaveTextContent('Overdue: Late Co')
    expect(blocked).not.toHaveTextContent('On hold')
    await user.click(within(blocked).getByRole('button', { name: 'Allow and close' }))
    expect(within(blocked).getByText('Give a reason to allow the credit.')).toBeInTheDocument()
    await user.click(within(blocked).getByRole('textbox', { name: /Reason for allowing/ }))
    await user.paste('Paying by cheque tomorrow')
    await user.click(within(blocked).getByRole('button', { name: 'Allow and close' }))

    expect(shiftApi.closeShift).toHaveBeenLastCalledWith(3, 11,
      expect.objectContaining({ allowBlockedCredit: true, blockedCreditReason: 'Paying by cheque tomorrow' }), expect.any(String))
  })

  it('tells a manager to ask the owner when credit is blocked, with no way to allow it', async () => {
    useAuthStore.setState({ user: { userId: 2, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    vi.mocked(shiftApi.closeShift).mockRejectedValueOnce(blockedCreditRefusal())
    const user = await fillShift('1000')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    const blocked = await screen.findByRole('alert', { name: 'Credit for these clients is blocked' })
    expect(blocked).toHaveTextContent('Ask the owner to approve, or go back and remove the entry.')
    expect(within(blocked).queryByRole('button', { name: 'Allow and close' })).not.toBeInTheDocument()
  })

  it('lists what is still needed and points the disabled Review button at that list', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Close Shift' })).toBeInTheDocument()
    const review = screen.getByRole('button', { name: 'Review close' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed End reading for 1 nozzle Payments collected')

    await user.click(endReading())
    await user.paste('1010')
    expect(review).toHaveAccessibleDescription('Still needed Payments collected')

    await user.click(await cashTotalField(user))
    await user.paste('1000')
    expect(review).toBeEnabled()
    expect(review).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByText('Still needed')).not.toBeInTheDocument()
  })

  it('asks to finish an incomplete credit entry before review', async () => {
    const user = userEvent.setup()
    const { container } = renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('1000')
    await user.click(screen.getByRole('button', { name: 'Add credit sale' }))

    expect(screen.getByRole('button', { name: 'Review close' })).toBeDisabled()
    expect(screen.getByText('Finish 1 credit entry')).toBeInTheDocument()
    expect(container.querySelector('.bg-warn-bg')).toBeNull()
  })

  it('keeps the success header until readings and payment are entered, then turns red for a shortage', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    const header = screen.getByRole('heading', { name: 'Close Shift' }).closest('.ui-modal-header')
    expect(header).toHaveClass('ui-modal-header--success')
    await user.click(endReading())
    await user.paste('1010')
    expect(header).toHaveClass('ui-modal-header--success')

    await user.click(await cashTotalField(user))
    await user.paste('900')
    expect(header).toHaveClass('ui-modal-header--danger')
  })

  it('asks inside the app before discarding entered details', async () => {
    const onClose = vi.fn()
    const browserConfirm = vi.spyOn(window, 'confirm')
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={onClose} />)
    await user.click(endReading())
    await user.paste('1010')

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('dialog', { name: 'Discard what you entered?' })).toBeInTheDocument()
    expect(browserConfirm).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Keep editing' }))
    expect(onClose).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('counts the bag by note by default and sends the breakdown with the counted total', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(endReading())
    await user.paste('1010')
    await user.type(screen.getByLabelText('Number of ₹500 notes'), '1')
    await user.click(screen.getByRole('button', { name: 'Add one ₹100 note' }))
    await user.type(screen.getByLabelText('Number of ₹200 notes'), '2')

    expect(within(screen.getByRole('region', { name: 'Cash (₹)' })).getByText('₹1,000.00')).toBeInTheDocument()
    expect(screen.getByText('Balanced')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({
      cashCollected: 1000,
      cashDenominations: { notes500: 1, notes200: 2, notes100: 1, notes50: 0, notes20: 0, notes10: 0, coins: 0 },
    }), expect.any(String))
  })

  it('sends no breakdown when the total is typed instead', async () => {
    const user = await fillShift('1000')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(vi.mocked(shiftApi.closeShift).mock.calls[0][2]).not.toHaveProperty('cashDenominations')
  })

  it('takes confirmed drops off the cash expected in the bag', async () => {
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([drop({ id: 1, amount: 600, status: 'CONFIRMED' })])
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)
    await user.click(endReading())
    await user.paste('1010')

    const figures = await screen.findByLabelText('Cash in the safe and in the bag')
    expect(within(figures).getByText('Dropped to safe').nextSibling).toHaveTextContent('₹600.00')
    expect(within(figures).getByText('Expected in bag').nextSibling).toHaveTextContent('₹400.00')

    await user.click(await cashTotalField(user, 'Cash in bag (₹)'))
    await user.paste('400')
    expect(screen.getByText('Balanced')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({ cashCollected: 400 }), expect.any(String))
  })

  it('holds the close until a waiting drop is confirmed or rejected, and confirms it in place', async () => {
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([drop({ id: 7, amount: 300, status: 'PENDING' })])
    vi.mocked(cashApi.confirmDrop).mockResolvedValue(drop({ id: 7, amount: 300, status: 'CONFIRMED' }))
    const user = await fillShift('1000')

    expect(await screen.findByText('1 drop is waiting. Confirm or reject it before closing.')).toBeInTheDocument()
    const review = screen.getByRole('button', { name: 'Review close' })
    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed Confirm or reject 1 cash drop')

    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(cashApi.confirmDrop).toHaveBeenCalledWith(3, 7)
  })



  it('rounds a gap under one rupee into cash so the shift balances, and offers the same for a small excess', async () => {
    const user = await fillShift('999.40')
    expect(screen.getByText('Shift is short by ₹0.60')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Round off ₹0.60 into cash' }))
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(1000)
    expect(within(screen.getByRole('dialog')).getByText('Balanced')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Cash (₹)'))
    await user.click(screen.getByLabelText('Cash (₹)'))
    await user.paste('1000.50')
    expect(screen.getByText('Over ₹0.50')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Round off ₹0.50 into cash' })).toBeInTheDocument()
  })

  it('corrects a pre-recorded credit entry by closing, without voiding it first', async () => {
    const user = userEvent.setup()
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([{
      id: 3, pumpId: 3, name: 'Acme', phone: null,
      parentClientId: null, parentClientName: null, isParent: false, isActive: true,
    }])
    const recordedEntry = {
      id: 41, clientId: 3, clientName: 'Acme', billNo: null, amount: 200, fuelType: 'PETROL',
      description: null, voidStatus: 'ACTIVE' as const, voidReason: null,
    }
    vi.mocked(shiftApi.getShift).mockResolvedValue({ ...shift, creditEntries: [recordedEntry] })
    renderWithProviders(<CloseShiftModal shift={{ ...shift, creditEntries: [recordedEntry] }} onClose={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('800')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    expect(shiftApi.voidCreditEntry).not.toHaveBeenCalled()
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({
      creditTotal: 200,
      replacedCreditEntryIds: [41],
      creditEntries: [expect.objectContaining({ clientId: 3, amount: 200 })],
    }), expect.any(String))
  })

  it('needs a reason for a shortage, reviews every figure, goes back on Edit and closes with the reason', async () => {
    const user = await fillShift('900')
    expect(screen.getByText('Short ₹100.00')).toBeInTheDocument()
    expect(screen.getByText('Shift is short by ₹100.00')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Round off/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review close' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Review close' })).toHaveAccessibleDescription('Still needed Reason for the difference')

    await user.click(screen.getByRole('textbox'))
    await user.paste('Customer paid later')
    await user.click(screen.getByRole('button', { name: 'Review close' }))

    const review = screen.getByRole('dialog', { name: 'Check before closing' })
    expect(screen.getByRole('heading', { name: 'Check before closing' })).toHaveFocus()
    expect(within(review).getByRole('row', { name: /Nozzle #1 · Petrol/ })).toHaveTextContent('10.00 L₹1,000.00')
    expect(within(review).getByRole('row', { name: /Total due/ })).toHaveTextContent('₹1,000.00')
    expect(within(review).getByText('Cash').nextSibling).toHaveTextContent('₹900.00')
    expect(within(review).getByText('Credit (0 entries)')).toBeInTheDocument()
    expect(within(review).getByText('Short ₹100.00')).toBeInTheDocument()
    expect(within(review).getByText('Reason: Customer paid later')).toBeInTheDocument()
    expect(await within(review).findByText(/more than the ₹50.00 limit/)).toBeInTheDocument()
    expect(review.querySelector('.ui-modal-backdrop')).toHaveClass('ui-modal-backdrop--fullscreen')
    expect(within(review).getByRole('button', { name: 'Confirm close' }).parentElement).toHaveClass('ui-modal-footer--large')

    await user.click(within(review).getByRole('button', { name: 'Edit' }))
    expect(shiftApi.closeShift).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(900)
    expect(screen.getByRole('dialog', { name: 'Close Shift' }).querySelector('.ui-modal-backdrop'))
      .not.toHaveClass('ui-modal-backdrop--fullscreen')

    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({ discrepancyReason: 'Customer paid later' }), expect.any(String))
  })

  it('after closing shows one summary instead of a toast, and offers the next shift, print and done', async () => {
    const onClose = vi.fn()
    const onOpenNextShift = vi.fn()
    const closed = { ...shift, status: 'CLOSED_BALANCED' as const, actualEndTime: '2026-03-15T08:30:00Z', totalAmountDue: 1000 }
    vi.mocked(shiftApi.closeShift).mockResolvedValue(closed)
    const user = userEvent.setup()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={onClose} onOpenNextShift={onOpenNextShift} />)
    await user.click(endReading())
    await user.paste('1010')
    await user.click(await cashTotalField(user))
    await user.paste('1000')
    expect(screen.getByText('Balanced')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11, expect.objectContaining({
      fuelReadings: [{ nozzleId: 21, endReading: 1010 }],
      cashCollected: 1000,
      discrepancyReason: undefined,
    }), expect.any(String))

    const summary = await screen.findByRole('dialog', { name: 'Shift closed' })
    expect(within(summary).getByText('Balanced')).toBeInTheDocument()
    expect(within(summary).getByText('Total due').nextSibling).toHaveTextContent('₹1,000.00')
    expect(within(summary).getByText('Credit entries').nextSibling).toHaveTextContent('0')

    await user.click(within(summary).getByRole('button', { name: 'Print / share' }))
    const report = await screen.findByRole('dialog', { name: 'Shift handover report' })
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
    await first.click(screen.getByRole('button', { name: 'Review close' }))
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
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    await waitFor(() => expect(shiftApi.closeShift).toHaveBeenCalledTimes(3))
    expect(vi.mocked(shiftApi.closeShift).mock.calls[2][3]).toBe(firstKey)
  })

  it('sends a new key once the figures change after a failed attempt', async () => {
    vi.mocked(shiftApi.closeShift).mockRejectedValueOnce(new AxiosError('timeout', 'ECONNABORTED'))
    const user = await fillShift('1000')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    await screen.findByRole('alert')

    await user.click(screen.getByRole('button', { name: 'Edit' }))
    await user.click(screen.getByLabelText('UPI (₹)'))
    await user.paste('100')
    await user.clear(screen.getByLabelText('Cash (₹)'))
    await user.click(await cashTotalField(user))
    await user.paste('900')
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    await waitFor(() => expect(shiftApi.closeShift).toHaveBeenCalledTimes(2))
    const [firstCall, secondCall] = vi.mocked(shiftApi.closeShift).mock.calls
    expect(secondCall[3]).not.toBe(firstCall[3])
  })
})
