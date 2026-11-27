import { describe, expect, it } from 'vitest'
import { operatorCollectionsOf, splitTotalOf } from './operatorCollections'

describe('operator collections', () => {
  it('sends no split when nothing was typed', () => {
    expect(operatorCollectionsOf({ 9: {}, 12: { cashCollected: '' } })).toEqual([])
  })

  it('sends every operator once anything is typed, with blanks as zero', () => {
    expect(operatorCollectionsOf({ 9: { cashCollected: '1450' }, 12: { cashCollected: '1500', upiCollected: '50' } })).toEqual([
      { operatorId: 9, cashCollected: 1450, upiCollected: 0, cardCollected: 0, fleetCardCollected: 0, creditTotal: 0 },
      { operatorId: 12, cashCollected: 1500, upiCollected: 50, cardCollected: 0, fleetCardCollected: 0, creditTotal: 0 },
    ])
  })

  it('adds up one figure across the operators', () => {
    expect(splitTotalOf({ 9: { cashCollected: '1450' }, 12: { cashCollected: '1550.50' } }, 'cashCollected')).toBe(3000.5)
  })
})
