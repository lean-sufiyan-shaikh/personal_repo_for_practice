import { describe, expect, it } from 'vitest'
import { groupPhoneDigits, isCompletePhone, phoneDigits } from './phoneDigits'

describe('phoneDigits', () => {
  it.each(['+91 98765-43210', '098765 43210', '9876543210', '919876543210'])('turns %s into the 10 digits', (text) => {
    expect(phoneDigits(text)).toBe('9876543210')
  })

  it('keeps at most 10 digits', () => {
    expect(phoneDigits('98765432109')).toBe('9876543210')
  })
})

describe('groupPhoneDigits', () => {
  it('groups the digits 5 + 5 once there are more than five', () => {
    expect(groupPhoneDigits('98765')).toBe('98765')
    expect(groupPhoneDigits('987654')).toBe('98765 4')
    expect(groupPhoneDigits('9876543210')).toBe('98765 43210')
  })
})

describe('isCompletePhone', () => {
  it('needs exactly 10 digits', () => {
    expect(isCompletePhone('9876543210')).toBe(true)
    expect(isCompletePhone('98765')).toBe(false)
    expect(isCompletePhone('98765 4321')).toBe(false)
  })
})
