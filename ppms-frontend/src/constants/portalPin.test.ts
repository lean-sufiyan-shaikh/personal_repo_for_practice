import { describe, expect, it } from 'vitest'
import { PORTAL_PIN_PATTERN, portalPinProblem } from './portalPin'

describe('portalPinProblem', () => {
  it.each([
    ['48297', 'length'],
    ['4829710', 'length'],
    ['000000', 'repeated'],
    ['555555', 'repeated'],
    ['234567', 'sequential'],
    ['876543', 'sequential'],
    ['123123', 'common'],
    ['147258', 'common'],
    ['520520', 'common'],
  ])('refuses %s as %s', (pin, problem) => {
    expect(portalPinProblem(pin)).toBe(problem)
  })

  it('accepts a 6-digit PIN that is not easy to guess', () => {
    expect(portalPinProblem('482971')).toBeNull()
    expect(portalPinProblem('901234')).toBeNull()
  })

  it('still lets the balance page take the 4 to 6 digit PINs set before 6 digits were required', () => {
    expect(PORTAL_PIN_PATTERN.test('4821')).toBe(true)
    expect(PORTAL_PIN_PATTERN.test('482971')).toBe(true)
    expect(PORTAL_PIN_PATTERN.test('123')).toBe(false)
  })
})
