import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import { shiftApi } from '../../api/shiftApi'
import { userApi } from '../../api/userApi'
import { cashApi } from '../../api/cashApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import CloseShiftModal from './CloseShiftModal'
import { cashTotalField, endReading, fillShift, operator, shift } from './closeShiftModalTestData'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCreditClients: vi.fn(), getMyPumps: vi.fn() } }))
vi.mock('../../api/shiftApi', () => ({
  shiftApi: { closeShift: vi.fn(), createHandover: vi.fn(), voidCreditEntry: vi.fn(), getShift: vi.fn(), recordBlindCount: vi.fn() },
}))
vi.mock('../../api/userApi', () => ({ userApi: { getOperators: vi.fn() } }))
vi.mock('../../api/cashApi', () => ({ cashApi: { getShiftDrops: vi.fn(), confirmDrop: vi.fn(), rejectDrop: vi.fn() } }))

function setupUser() {
  return userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
}

async function passSaveDelay() {
  await act(() => vi.advanceTimersByTimeAsync(500))
}

describe('CloseShiftModal drafts', () => {
  afterEach(() => vi.useRealTimers())

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.mocked(pumpApi.getCreditClients).mockResolvedValue([])
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([])
    vi.mocked(shiftApi.getShift).mockResolvedValue(shift)
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([])
    vi.mocked(userApi.getOperators).mockResolvedValue([operator(12, 'Sita')])
  })

  it('saves entries on this device shortly after typing, separately for close and handover', async () => {
    await fillShift('900', setupUser())

    await passSaveDelay()
    expect(localStorage.getItem('ppms_draft:close-shift:11')).not.toBeNull()
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
    const user = setupUser()
    renderWithProviders(<CloseShiftModal shift={shift} onClose={vi.fn()} />)

    expect(screen.getByText(/^Resumed your unsaved entries from \d{2}:\d{2} (am|pm)$/i)).toBeInTheDocument()
    expect(endReading()).toHaveValue(1010)
    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(700)

    await user.click(screen.getByRole('button', { name: 'Discard' }))
    expect(screen.queryByText(/Resumed your unsaved entries/)).not.toBeInTheDocument()
    expect(endReading()).toHaveValue(null)
    expect(screen.getByRole('button', { name: 'Just type the total' })).toBeInTheDocument()
    expect(localStorage.getItem('ppms_draft:close-shift:11')).toBeNull()
  })

  it('clears the draft once the shift is closed', async () => {
    vi.mocked(shiftApi.closeShift).mockResolvedValue({ ...shift, status: 'CLOSED_BALANCED' })
    const user = await fillShift('1000', setupUser())
    await passSaveDelay()
    expect(localStorage.getItem('ppms_draft:close-shift:11')).not.toBeNull()

    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))
    await screen.findByRole('heading', { name: 'Shift closed' })

    expect(localStorage.getItem('ppms_draft:close-shift:11')).toBeNull()
    await passSaveDelay()
    expect(localStorage.getItem('ppms_draft:close-shift:11')).toBeNull()
  })

  it('still works when the browser refuses storage', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })

    await fillShift('1000', setupUser())

    expect(screen.getByText('Balanced')).toBeInTheDocument()
    await passSaveDelay()
    await waitFor(() => expect(warn).toHaveBeenCalledWith(expect.stringContaining('Could not save the draft'), expect.any(Error)))
    setItem.mockRestore()
    warn.mockRestore()
  })

  it('asks for the blind count before showing any reading or expected amount, then closes with the counted figures', async () => {
    const user = setupUser()
    const blindShift = { ...shift, blindCountRequired: true, blindCount: null }
    const counted = {
      ...blindShift,
      blindCount: { cashCollected: 900, cashDenominations: null, upiCollected: 100, cardCollected: 0, fleetCardCollected: 0,
        countedAt: '2026-03-15T08:00:00Z', countedByName: 'Ravi' },
    }
    vi.mocked(shiftApi.getShift).mockResolvedValue(blindShift)
    vi.mocked(shiftApi.recordBlindCount).mockResolvedValue(counted)
    renderWithProviders(<CloseShiftModal shift={blindShift} onClose={vi.fn()} />)

    expect(screen.queryByLabelText('Nozzle #1 · Petrol end reading')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review close' })).toBeDisabled()
    await user.click(await cashTotalField(user, 'Cash in bag (₹)'))
    await user.paste('900')
    await user.click(screen.getByLabelText('UPI (₹)'))
    await user.paste('100')
    vi.mocked(shiftApi.getShift).mockResolvedValue(counted)
    await user.click(screen.getByRole('button', { name: 'Save the count' }))

    expect(shiftApi.recordBlindCount).toHaveBeenCalledWith(3, 11,
      expect.objectContaining({ cashCollected: 900, upiCollected: 100, cardCollected: 0, fleetCardCollected: 0 }))
    await user.click(await screen.findByLabelText('Nozzle #1 · Petrol end reading'))
    await user.paste('1010')
    expect(screen.queryByLabelText('UPI (₹)')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Review close' }))
    await user.click(screen.getByRole('button', { name: 'Confirm close' }))

    expect(shiftApi.closeShift).toHaveBeenCalledWith(3, 11,
      expect.objectContaining({ cashCollected: 900, upiCollected: 100, cardCollected: 0, fleetCardCollected: 0 }), expect.any(String))
  })
})
