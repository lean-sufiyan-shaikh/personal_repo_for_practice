import { describe, expect, it } from 'vitest'
import type { TankStock } from '../../../api/inventoryApi'
import i18n from '../../../i18n'
import type { DeliveryRow } from './deliveryRows'
import { prepareDeliveryItems } from './prepareDeliveryItems'
import { emptyReceiptCheck } from './receiptCheck'

const tank: TankStock = {
  tankId: 1, tankIdentifier: 'T1', fuelType: 'PETROL', capacity: 10_000, currentStock: 1_000,
  stockPercentage: 10, lowStock: true, dipTolerance: 50, pumpId: 6,
}

const row: DeliveryRow = {
  id: 1, tankId: '1', qty: '1000', costPrice: '90',
  check: { ...emptyReceiptCheck(), dipBefore: '1000', dipAfter: '1990', observedDensity: '830.5', invoiceDensity: '826' },
}

function prepare(overrides: { date?: string; invoice?: string; rows?: DeliveryRow[]; isOwnerOrAdmin?: boolean } = {}) {
  return prepareDeliveryItems({
    date: '2026-11-27', invoice: 'INV-7', rows: [row], tanks: [tank], isOwnerOrAdmin: true,
    nozzlesForTank: () => [], tolerances: { receiptTolerancePercent: 0.2, densityTolerance: 3 },
    t: i18n.getFixedT<['inventory', 'common']>('en', ['inventory', 'common']), ...overrides,
  })
}

describe('prepareDeliveryItems', () => {
  it('prices each tank line and works out the stock after delivery from what was received', () => {
    const { items } = prepare()

    expect(items).toEqual([expect.objectContaining({
      tankId: 1, quantityDelivered: 1000, costPricePerUnit: 90, totalCost: 90_000, stockAfter: 1990,
      densityDifference: 4.5, densityFlagged: true,
    })])
    expect(items?.[0].receipt).toMatchObject({ kind: 'SHORT', receivedLitres: 990 })
  })

  it('sends no cost from someone who may not see costs', () => {
    expect(prepare({ isOwnerOrAdmin: false, rows: [{ ...row, costPrice: '' }] }).items?.[0]).toMatchObject({ costPricePerUnit: 0, totalCost: 0 })
  })

  it('refuses a delivery without a date or invoice number', () => {
    expect(prepare({ invoice: ' ' }).error).toBe('Delivery date and invoice number are required.')
  })

  it('refuses the same tank twice, a row without a tank and a quantity of zero', () => {
    expect(prepare({ rows: [row, { ...row, id: 2 }] }).error).toMatch(/^The same tank is selected more than once/)
    expect(prepare({ rows: [{ ...row, tankId: '' }] }).error).toBe('Please select a tank for every row.')
    expect(prepare({ rows: [{ ...row, qty: '0' }] }).error).toBe('Quantity must be greater than 0 for every row.')
  })

  it('refuses more than the free space in the tank', () => {
    expect(prepare({ rows: [{ ...row, qty: '9500' }] }).error).toBe('T1: quantity (9,500.00 L) exceeds available space (9,000.00 L).')
  })

  it('needs a cost and a finished receipt check on every row', () => {
    expect(prepare({ rows: [{ ...row, costPrice: '0' }] }).error).toBe('Cost per litre must be greater than 0 for every row.')
    expect(prepare({ rows: [{ ...row, check: emptyReceiptCheck() }] }).error).toBe('Complete the receipt check for every tank.')
  })
})
