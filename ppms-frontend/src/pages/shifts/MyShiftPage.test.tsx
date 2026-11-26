import { screen, waitFor, within } from '@testing-library/react'
import { AxiosError } from 'axios'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { cashApi } from '../../api/cashApi'
import type { CashDrop } from '../../api/cashApi'
import type { FuelPrice } from '../../api/pumpApi'
import type { Shift } from '../../types/shift'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import MyShiftPage from './MyShiftPage'

vi.mock('../../api/shiftApi', () => ({ shiftApi: { getActiveShifts: vi.fn() } }))
vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCurrentPrices: vi.fn() } }))
vi.mock('../../api/cashApi', () => ({ cashApi: { getShiftDrops: vi.fn(), recordDrop: vi.fn() } }))
vi.mock('./modals/AddCreditEntryModal', () => ({
  AddCreditEntryModal: ({ shift }: { shift: Shift }) => <div role="dialog" aria-label={`Credit sale for shift ${shift.id}`} />,
}))

const openShift = {
  id: 11, pumpId: 4, duId: 2, duNumber: 1, duName: 'DU A', operatorName: 'Raju', status: 'OPEN',
  actualStartTime: new Date(Date.now() - (3 * 60 + 10) * 60_000).toISOString(),
  nozzles: [{ id: 5, nozzleNumber: 1, fuelType: 'PETROL' }, { id: 6, nozzleNumber: 2, fuelType: 'DIESEL' }],
  fuelReadings: [
    { nozzleId: 5, fuelType: 'PETROL', priceSnapshot: 100 },
    { nozzleId: 6, fuelType: 'DIESEL', priceSnapshot: 90 },
  ],
  creditEntries: [
    { id: 1, clientName: 'Fleet Co', amount: 1000, voidStatus: 'ACTIVE' },
    { id: 2, clientName: 'Acme', amount: 250, voidStatus: 'ACTIVE' },
    { id: 3, clientName: 'Acme', amount: 500, voidStatus: 'VOIDED' },
  ],
} as unknown as Shift

const petrolPrice = { fuelType: 'PETROL', pricePerUnit: 102.5 } as FuelPrice

function drop(overrides: Partial<CashDrop>): CashDrop {
  return {
    id: 1, shiftId: 11, operatorId: 9, operatorName: 'Raju', amount: 500, denominations: null, status: 'PENDING',
    recordedByName: 'Raju', recordedAt: '2026-03-15T03:00:00Z', reviewedByName: null, reviewedAt: null,
    rejectionReason: null, ...overrides,
  }
}

describe('MyShiftPage', () => {
  beforeEach(() => {
    usePumpStore.setState({ selectedPumpId: 4, suspendedPumpId: null })
    vi.mocked(pumpApi.getCurrentPrices).mockResolvedValue([petrolPrice])
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([])
  })

  it('shows the operator their DU, nozzles, prices, time on shift and credit so far', async () => {
    vi.mocked(shiftApi.getActiveShifts).mockResolvedValue([openShift])
    renderWithProviders(<MyShiftPage />)

    expect(await screen.findByRole('heading', { name: 'DU A' })).toBeInTheDocument()
    expect(screen.getByText('Nozzle #1 Petrol · #2 Diesel')).toBeInTheDocument()
    expect(await screen.findByText('₹102.50/L')).toBeInTheDocument()
    expect(screen.getByText('₹90.00/L')).toBeInTheDocument()
    expect(screen.getByText('3h 10m')).toBeInTheDocument()
    expect(screen.getByText('Credit entries').nextElementSibling).toHaveTextContent('2')
    expect(screen.getByText('₹1,250.00')).toBeInTheDocument()
  })

  it('opens the credit sale form for the shift from the large button', async () => {
    vi.mocked(shiftApi.getActiveShifts).mockResolvedValue([openShift])
    renderWithProviders(<MyShiftPage />)

    await userEvent.setup().click(await screen.findByRole('button', { name: '+ Credit sale' }))

    expect(screen.getByRole('dialog', { name: 'Credit sale for shift 11' })).toBeInTheDocument()
  })

  it('tells the operator to ask the manager when no shift is open', async () => {
    vi.mocked(shiftApi.getActiveShifts).mockResolvedValue([])
    renderWithProviders(<MyShiftPage />)

    expect(await screen.findByText('No open shift.')).toBeInTheDocument()
    expect(screen.getByText('Ask your manager to open your shift.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '+ Credit sale' })).not.toBeInTheDocument()
  })

  it('lists the drops of the shift with their status and the confirmed total', async () => {
    vi.mocked(shiftApi.getActiveShifts).mockResolvedValue([openShift])
    vi.mocked(cashApi.getShiftDrops).mockResolvedValue([
      drop({ id: 2, amount: 700, status: 'PENDING' }),
      drop({ id: 1, amount: 1500, status: 'CONFIRMED', reviewedByName: 'Meena' }),
    ])
    renderWithProviders(<MyShiftPage />)

    const drops = await screen.findByRole('region', { name: 'Cash drops this shift' })
    expect(await within(drops).findByText('Waiting for confirmation')).toBeInTheDocument()
    expect(within(drops).getByText('Confirmed in safe')).toBeInTheDocument()
    expect(within(drops).getByText(/confirmed by Meena/)).toBeInTheDocument()
    expect(within(drops).getByText(/Confirmed so far/)).toHaveTextContent('Confirmed so far ₹1,500.00')
    expect(within(drops).getByText('1 drop waiting for a manager')).toBeInTheDocument()
    expect(within(drops).queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
  })

  it('records a counted drop once, keeping the same key when it is resent', async () => {
    vi.mocked(shiftApi.getActiveShifts).mockResolvedValue([openShift])
    vi.mocked(cashApi.recordDrop)
      .mockRejectedValueOnce(new AxiosError('timeout', 'ECONNABORTED'))
      .mockResolvedValueOnce(drop({ id: 3, amount: 1100 }))
    const user = userEvent.setup()
    renderWithProviders(<MyShiftPage />)

    await user.click(await screen.findByRole('button', { name: 'Drop cash to safe' }))
    const dialog = screen.getByRole('dialog', { name: 'Drop cash to safe' })
    const record = within(dialog).getByRole('button', { name: 'Record drop' })
    expect(record).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Number of ₹500 notes'), '2')
    await user.type(within(dialog).getByLabelText('Number of ₹100 notes'), '1')
    await user.click(record)
    await user.click(await within(dialog).findByRole('button', { name: 'Record drop' }))

    await waitFor(() => expect(cashApi.recordDrop).toHaveBeenCalledTimes(2))
    const [first, second] = vi.mocked(cashApi.recordDrop).mock.calls
    expect(first.slice(0, 3)).toEqual([4, 11, {
      amount: 1100,
      denominations: { notes500: 2, notes200: 0, notes100: 1, notes50: 0, notes20: 0, notes10: 0, coins: 0 },
    }])
    expect(second[3]).toBe(first[3])
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Drop cash to safe' })).not.toBeInTheDocument())
  })

  it('records a typed total without a breakdown', async () => {
    vi.mocked(shiftApi.getActiveShifts).mockResolvedValue([openShift])
    vi.mocked(cashApi.recordDrop).mockResolvedValue(drop({ id: 4, amount: 800 }))
    const user = userEvent.setup()
    renderWithProviders(<MyShiftPage />)

    await user.click(await screen.findByRole('button', { name: 'Drop cash to safe' }))
    await user.click(screen.getByRole('button', { name: 'Just type the total' }))
    await user.type(screen.getByLabelText('Cash going into the safe'), '800')
    await user.click(screen.getByRole('button', { name: 'Record drop' }))

    await waitFor(() => expect(cashApi.recordDrop).toHaveBeenCalledWith(4, 11, { amount: 800 }, expect.any(String)))
  })
})
