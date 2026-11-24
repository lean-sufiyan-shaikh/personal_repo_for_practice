import { describe, expect, it } from 'vitest'
import { fmtCurrency, fmtDate, parseSellingPrice } from './ancillaryFormat'

describe('fmtCurrency', () => {
  it('formats rupees with Indian grouping and two decimals', () => {
    expect(fmtCurrency(123456.5)).toBe('₹1,23,456.50')
    expect(fmtCurrency(0)).toBe('₹0.00')
  })

  it('shows a dash for a missing value', () => {
    expect(fmtCurrency(null)).toBe('—')
    expect(fmtCurrency(undefined)).toBe('—')
  })
})

describe('parseSellingPrice', () => {
  it('reads a price above zero with up to two decimals', () => {
    expect(parseSellingPrice('450')).toBe(450)
    expect(parseSellingPrice(' 12.5 ')).toBe(12.5)
    expect(parseSellingPrice('0.01')).toBe(0.01)
  })

  it('refuses zero, blanks, negatives and more than two decimals', () => {
    expect(parseSellingPrice('0')).toBeNull()
    expect(parseSellingPrice('')).toBeNull()
    expect(parseSellingPrice('-5')).toBeNull()
    expect(parseSellingPrice('12.345')).toBeNull()
  })
})

describe('fmtDate', () => {
  it('formats an ISO date as an IST date', () => {
    expect(fmtDate('2026-03-14T19:00:00Z')).toBe('15 Mar 2026')
  })
})
