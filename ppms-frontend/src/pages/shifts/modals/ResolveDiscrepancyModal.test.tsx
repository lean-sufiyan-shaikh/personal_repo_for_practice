import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { Shift } from '../../../types/shift'
import { ResolveDiscrepancyModal } from './ResolveDiscrepancyModal'

vi.mock('../../../api/shiftApi', () => ({ shiftApi: { resolveDiscrepancy: vi.fn() } }))

const closedShift: Shift = {
  id: 11, pumpId: 3, duId: 5, duNumber: 1, duName: 'DU 1',
  nozzles: [{ id: 21, nozzleNumber: 1, fuelType: 'PETROL' }],
  operatorId: 9, operatorName: 'Ravi', openedByUserName: 'Owner', shiftWindow: 'MORNING',
  shiftDate: '2026-03-15', actualStartTime: '2026-03-15T00:30:00Z', actualEndTime: '2026-03-15T08:30:00Z', scheduledEndTime: null,
  fuelReadings: [],
  totalAmountDue: 1000, cashCollected: 1100, upiCollected: 0, cardCollected: 0, fleetCardCollected: 0,
  creditTotal: 0, discrepancyAmount: 100, discrepancyType: 'OVER', discrepancyReason: 'Extra cash',
  discrepancyResolution: null, discrepancyResolutionNote: null, cashRecoveryAmount: null,
  status: 'CLOSED_DISCREPANCY_PENDING', isBackfilled: false, creditEntries: [],
}

async function resolutionChoices(shift: Shift) {
  const user = userEvent.setup()
  renderWithProviders(<table><tbody><ResolveDiscrepancyModal shift={shift} onClose={vi.fn()} /></tbody></table>)
  await user.click(screen.getByRole('button', { name: /Resolution Action/ }))
  return within(await screen.findByRole('listbox', { name: 'Resolution Action' }))
}

describe('ResolveDiscrepancyModal', () => {
  it('does not offer salary deduction for an OVER discrepancy', async () => {
    const choices = await resolutionChoices(closedShift)

    expect(choices.queryByRole('option', { name: 'Salary Deduction' })).not.toBeInTheDocument()
    expect(choices.getByRole('option', { name: 'Waived' })).toBeInTheDocument()
  })

  it('offers salary deduction for a SHORT discrepancy', async () => {
    const choices = await resolutionChoices({ ...closedShift, discrepancyType: 'SHORT', cashCollected: 900 })

    expect(choices.getByRole('option', { name: 'Salary Deduction' })).toBeInTheDocument()
  })
})
