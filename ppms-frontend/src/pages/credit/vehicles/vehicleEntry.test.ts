import { describe, expect, it } from 'vitest'
import { normaliseRegistration, vehicleRequestFields } from './vehicleEntry'

describe('vehicleEntry', () => {
  it('normalises a registration the way the server stores it', () => {
    expect(normaliseRegistration(' mh-12 ab.1234 ')).toBe('MH12AB1234')
    expect(normaliseRegistration('22 BH/1234-AA')).toBe('22BH1234AA')
  })

  it('sends a picked vehicle by id, a typed one by registration, and leaves blank parts out', () => {
    expect(vehicleRequestFields({ vehicleId: 7, registration: 'MH12AB1234', driverName: ' Ravi ', driverPhone: '' }))
      .toEqual({ vehicleId: 7, vehicleRegistration: undefined, driverName: 'Ravi', driverPhone: undefined })
    expect(vehicleRequestFields({ vehicleId: null, registration: ' ka01 zz 9999 ', driverName: '', driverPhone: '9876543210' }))
      .toEqual({ vehicleId: undefined, vehicleRegistration: 'ka01 zz 9999', driverName: undefined, driverPhone: '9876543210' })
  })
})
