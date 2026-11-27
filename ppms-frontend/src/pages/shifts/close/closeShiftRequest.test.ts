import { describe, expect, it } from 'vitest'
import { EMPTY_VEHICLE_ENTRY } from '../../credit/vehicles/vehicleEntry'
import type { FuelReading } from '../../../types/shift'
import type { CreditRow } from '../components/creditRow'
import { buildCloseShiftRequest } from './closeShiftRequest'

const fuelReadings: FuelReading[] = [{
  nozzleId: 7, fuelType: 'DIESEL', startReading: 10, endReading: null,
  priceSnapshot: 90, unitsSold: null, maxMeterValue: null,
}]

function creditRow(overrides: Partial<CreditRow> = {}): CreditRow {
  return {
    id: 1, clientId: 3, clientName: ' Acme ', clientSearch: '', showDropdown: false,
    subAccountId: null, subAccountName: '', fuelType: 'DIESEL', billNo: ' B1 ', amount: '90',
    liters: '', inputMode: 'amount', description: '', replacesEntryId: null, vehicle: EMPTY_VEHICLE_ENTRY, ...overrides,
  }
}

const base = {
  fuelReadings, endReadings: { 7: '12' }, readingsBelowStart: false, creditRows: [] as CreditRow[],
  cash: '90', upi: '', card: '', fleetCard: '', creditTotal: 0, discrepancyReason: '  ',
}

describe('buildCloseShiftRequest', () => {
  it('builds the request with empty payments sent as zero and a blank reason left out', () => {
    const result = buildCloseShiftRequest(base)
    expect(result).toEqual({
      request: {
        fuelReadings: [{ nozzleId: 7, endReading: 12 }],
        cashCollected: 90, upiCollected: 0, cardCollected: 0, fleetCardCollected: 0,
        creditTotal: 0, creditEntries: [], replacedCreditEntryIds: [], discrepancyReason: undefined,
      },
    })
  })

  it('sends the sub-account id when one is chosen and trims text fields', () => {
    const result = buildCloseShiftRequest({ ...base, creditRows: [creditRow({ subAccountId: 9 })], creditTotal: 90 })
    expect('request' in result && result.request.creditEntries).toEqual([
      { clientId: 9, clientName: 'Acme', billNo: 'B1', amount: 90, fuelType: 'DIESEL', description: undefined },
    ])
  })

  it('sends the picked vehicle and the driver who filled with each credit entry', () => {
    const vehicle = { vehicleId: 7, registration: 'MH12AB1234', driverName: 'Suresh', driverPhone: '9876543210' }
    const result = buildCloseShiftRequest({ ...base, creditRows: [creditRow({ vehicle })], creditTotal: 90 })
    expect('request' in result && result.request.creditEntries[0]).toEqual(expect.objectContaining({
      vehicleId: 7, vehicleRegistration: undefined, driverName: 'Suresh', driverPhone: '9876543210',
    }))
  })

  it('refuses readings below the start reading', () => {
    expect(buildCloseShiftRequest({ ...base, readingsBelowStart: true })).toHaveProperty('error')
  })

  it.each<[string, Partial<CreditRow>]>([
    ['Each credit entry must have a client.', { clientName: '  ' }],
    ['Please select a client from the dropdown for each credit entry.', { clientId: null }],
    ['Each credit entry must have a fuel type.', { fuelType: '' }],
    ['Each credit entry amount must be > 0.', { amount: '0' }],
  ])('refuses a credit row: %s', (message, overrides) => {
    expect(buildCloseShiftRequest({ ...base, creditRows: [creditRow(overrides)] })).toEqual({ error: message })
  })

  it('refuses an invalid end reading', () => {
    expect(buildCloseShiftRequest({ ...base, endReadings: { 7: '-1' } }))
      .toEqual({ error: 'End reading contains an invalid value. Please re-enter.' })
  })

  it('refuses an invalid payment amount', () => {
    expect(buildCloseShiftRequest({ ...base, upi: 'abc' })).toEqual({ error: 'UPI amount contains an invalid value.' })
  })

  it('lists the pre-recorded entries that corrected rows replace so the close voids them', () => {
    const result = buildCloseShiftRequest({
      ...base,
      creditRows: [creditRow({ id: 1, replacesEntryId: 41 }), creditRow({ id: 2 }), creditRow({ id: 3, replacesEntryId: 57 })],
      creditTotal: 270,
    })
    expect('request' in result && result.request.replacedCreditEntryIds).toEqual([41, 57])
  })
})
