import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createElement } from 'react'
import { vi } from 'vitest'
import type { CashDrop } from '../../api/cashApi'
import type { StaffMember } from '../../api/userApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Shift } from '../../types/shift'
import CloseShiftModal from './CloseShiftModal'

export const shift: Shift = {
  id: 11, pumpId: 3, duId: 5, duNumber: 1, duName: 'DU 1',
  nozzles: [{ id: 21, nozzleNumber: 1, fuelType: 'PETROL' }],
  operatorId: 9, operatorName: 'Ravi', openedByUserName: 'Owner', shiftWindow: 'MORNING',
  shiftDate: '2026-03-15', actualStartTime: '2026-03-15T00:30:00Z', actualEndTime: null, scheduledEndTime: null,
  fuelReadings: [{
    nozzleId: 21, fuelType: 'PETROL', startReading: 1000, endReading: null,
    priceSnapshot: 100, unitsSold: null, maxMeterValue: 999_999,
  }],
  totalAmountDue: null, cashCollected: null, upiCollected: null, cardCollected: null, fleetCardCollected: null,
  creditTotal: null, cashDropped: null, cashDenominations: null, discrepancyAmount: null, discrepancyType: null, discrepancyReason: null,
  discrepancyResolution: null, discrepancyResolutionNote: null,
  investigationNote: null, investigatedByName: null, investigationStartedAt: null, cashRecoveryAmount: null,
  status: 'OPEN', isBackfilled: false, creditEntries: [],
}

export function operator(id: number, fullName: string): StaffMember {
  return {
    id, employeeId: `E${id}`, fullName, phoneNumber: null, gender: null, nightShiftConsent: false, role: 'OPERATOR',
    assignedPumpId: 3, status: 'ACTIVE', dailyRate: null, shift1HourlyRate: null, standardHourlyRate: null,
  }
}

export const endReading = () => screen.getByLabelText('Nozzle #1 · Petrol end reading')

/** Cash is counted by note by default; these tests type the total, as staff can with "Just type the total". */
export async function cashTotalField(user: ReturnType<typeof userEvent.setup>, label = 'Cash (₹)') {
  const typeTotal = screen.queryByRole('button', { name: 'Just type the total' })
  if (typeTotal) await user.click(typeTotal)
  return screen.getByLabelText(label)
}

export function drop(overrides: Partial<CashDrop>): CashDrop {
  return {
    id: 1, shiftId: 11, operatorId: 9, operatorName: 'Ravi', amount: 100, denominations: null, status: 'PENDING',
    recordedByName: 'Ravi', recordedAt: '2026-03-15T03:00:00Z', reviewedByName: null, reviewedAt: null,
    rejectionReason: null, ...overrides,
  }
}

export async function fillShift(cash: string, user = userEvent.setup()) {
  renderWithProviders(createElement(CloseShiftModal, { shift, onClose: vi.fn() }))
  await user.type(endReading(), '1010')
  await user.type(await cashTotalField(user), cash)
  return user
}
