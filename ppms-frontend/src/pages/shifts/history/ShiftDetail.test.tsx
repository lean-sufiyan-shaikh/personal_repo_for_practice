import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shiftApi } from '../../../api/shiftApi'
import { useAuthStore } from '../../../store/authStore'
import { useVisibleLayout } from '../../../test/layout'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { Shift } from '../../../types/shift'
import { ShiftDetail } from './ShiftDetail'

vi.mock('../../../api/shiftApi', () => ({ shiftApi: { getShift: vi.fn() } }))
vi.mock('../amend/AmendShiftModal', () => ({ AmendShiftModal: () => <div role="dialog" aria-label="Amend form" /> }))
vi.mock('../modals/ResolveDiscrepancyModal', () => ({ ResolveDiscrepancyModal: () => <div role="dialog" aria-label="Resolve form" /> }))

const shortShift = {
  id: 77, pumpId: 4, duName: 'DU 2', duNumber: 2, nozzles: [{ nozzleId: 3, nozzleNumber: 1, fuelType: 'PETROL' }],
  operatorName: 'Asha', shiftWindow: 'Day', shiftDate: '2026-11-20', actualStartTime: '2026-11-20T03:30:00Z',
  actualEndTime: '2026-11-20T11:30:00Z', fuelReadings: [], totalAmountDue: 10_000, cashCollected: 9_500, upiCollected: 0,
  cardCollected: 0, fleetCardCollected: 0, creditTotal: 0, discrepancyAmount: 500, discrepancyType: 'SHORT',
  discrepancyReason: 'Change given twice', discrepancyResolution: null, investigationNote: null,
  status: 'CLOSED_DISCREPANCY_PENDING', isBackfilled: false,
  creditEntries: [{ id: 1, clientName: 'Acme Transport', billNo: 'B-12', amount: 0 }],
} as unknown as Shift

describe('ShiftDetail', () => {
  useVisibleLayout()

  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Mgr', role: 'MANAGER', assignedPumpId: 4 }, isAuthenticated: true })
    vi.mocked(shiftApi.getShift).mockResolvedValue(shortShift)
  })

  it('shows who worked the shift, what was collected, the discrepancy and the credit sales', async () => {
    renderWithProviders(<ShiftDetail pumpId={4} shiftId={77} />)

    const detail = await screen.findByRole('article', { name: 'Shift #77' })
    expect(within(detail).getByText('Asha')).toBeInTheDocument()
    expect(within(detail).getByText('#1 Petrol')).toBeInTheDocument()
    expect(within(detail).getByRole('region', { name: 'Collections' })).toHaveTextContent('₹9,500.00')
    expect(within(detail).getByRole('region', { name: 'Discrepancy' })).toHaveTextContent('Change given twice')
    expect(within(detail).getByRole('region', { name: 'Credit sales' })).toHaveTextContent('Acme Transport')
    expect(shiftApi.getShift).toHaveBeenCalledWith(4, 77)
  })

  it('lets a manager resolve the discrepancy and opens the handover report in a side panel', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ShiftDetail pumpId={4} shiftId={77} />)

    await user.click(await screen.findByRole('button', { name: 'Resolve' }))
    expect(screen.getByRole('dialog', { name: 'Resolve form' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Print handover report' }))
    const report = screen.getByRole('dialog', { name: 'Shift handover report' })
    expect(report.querySelector('.ui-side-panel')).not.toBeNull()
  })

  it('offers Amend shift on a closed shift to an owner but not to a manager', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ShiftDetail pumpId={4} shiftId={77} />)
    await screen.findByRole('article', { name: 'Shift #77' })
    expect(screen.queryByRole('button', { name: 'Amend shift' })).not.toBeInTheDocument()

    useAuthStore.setState({ user: { userId: 2, fullName: 'Own', role: 'OWNER', assignedPumpId: 4 }, isAuthenticated: true })
    await user.click(await screen.findByRole('button', { name: 'Amend shift' }))
    expect(screen.getByRole('dialog', { name: 'Amend form' })).toBeInTheDocument()
  })

  it('says so when the shift cannot be found at this pump', async () => {
    vi.mocked(shiftApi.getShift).mockRejectedValue(new Error('not found'))
    renderWithProviders(<ShiftDetail pumpId={4} shiftId={999} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Shift #999 could not be found at this pump.')
  })
  it('shows litres priced at two prices, the rounding, a stock shortfall, a part recovery and every investigation note', async () => {
    vi.mocked(shiftApi.getShift).mockResolvedValue({
      ...shortShift,
      nozzles: [{ id: 3, nozzleNumber: 1, fuelType: 'PETROL' }],
      fuelReadings: [{ nozzleId: 3, fuelType: 'PETROL', startReading: 1000, endReading: 1030, priceSnapshot: 100, unitsSold: 30,
        maxMeterValue: 999_999, priceSegments: [
          { startReading: 1000, pricePerUnit: 100, startsAt: '2026-11-20T03:30:00Z', unitsSold: 10 },
          { startReading: 1010, pricePerUnit: 110, startsAt: '2026-11-20T06:00:00Z', unitsSold: 20 },
        ] }],
      roundingAdjustment: -0.5, stockShortfall: true, cashRecoveryAmount: 200,
      investigationHistory: [{ note: 'Checking CCTV', writtenByName: 'Meera', writtenAt: '2026-11-21T04:00:00Z' }],
    } as Shift)
    renderWithProviders(<ShiftDetail pumpId={4} shiftId={77} />)

    const detail = await screen.findByRole('article', { name: 'Shift #77' })
    expect(within(detail).getByText(/Nozzle #1 · Petrol: 10.00 L × ₹100.00 \+ 20.00 L × ₹110.00/)).toBeInTheDocument()
    expect(within(detail).getByText(/A difference of ₹0.50 was within the rounding allowance/)).toBeInTheDocument()
    expect(within(detail).getByText(/Record the missing delivery/)).toBeInTheDocument()
    expect(within(detail).getByText('₹200.00 recovered in cash; ₹300.00 still to deduct or waive.')).toBeInTheDocument()
    expect(within(detail).getByText('Checking CCTV')).toBeInTheDocument()
  })
})
