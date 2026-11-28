import { describe, expect, it } from 'vitest'
import { entryLitres } from './entryLitres'

describe('entryLitres', () => {
  it('takes the litres as typed in litres mode', () => {
    expect(entryLitres('liters', '3.5', 350, 100)).toBe(3.5)
    expect(entryLitres('liters', ' ', 350, 100)).toBeNull()
  })

  it('works the litres out from the amount at the shift price, to two decimals', () => {
    expect(entryLitres('amount', '', 250, 100)).toBe(2.5)
    expect(entryLitres('amount', '', 100, 3)).toBe(33.33)
  })

  it('is unknown without a price or an amount', () => {
    expect(entryLitres('amount', '', 250, null)).toBeNull()
    expect(entryLitres('amount', '', 0, 100)).toBeNull()
  })
})
