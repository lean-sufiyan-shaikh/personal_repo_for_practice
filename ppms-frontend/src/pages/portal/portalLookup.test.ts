import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import { isLookupComplete, lookupError } from './portalLookup'

function refusedWith(status: number, params: Record<string, unknown> = {}) {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status, statusText: '', headers: {}, config: { headers: new AxiosHeaders() }, data: { message: 'Refused', params },
  })
}

describe('isLookupComplete', () => {
  it('needs a pump, a 10-digit phone and a 4 to 6 digit PIN', () => {
    expect(isLookupComplete('3', '9876543210', '4821')).toBe(true)
    expect(isLookupComplete('3', '9876543210', '482193')).toBe(true)
    expect(isLookupComplete('3', '9876543210', '12')).toBe(false)
    expect(isLookupComplete('3', '98765', '4821')).toBe(false)
    expect(isLookupComplete(' ', '9876543210', '4821')).toBe(false)
  })
})

describe('lookupError', () => {
  it('gives one answer for a missing account and for refused details', () => {
    expect(lookupError(refusedWith(404))).toEqual({ kind: 'noMatch' })
    expect(lookupError(refusedWith(400))).toEqual({ kind: 'noMatch' })
  })

  it('waits 15 minutes after too many attempts unless the server names a longer lock', () => {
    expect(lookupError(refusedWith(429))).toEqual({ kind: 'tooManyAttempts', minutes: 15 })
    expect(lookupError(refusedWith(429, { minutes: 60 }))).toEqual({ kind: 'tooManyAttempts', minutes: 60 })
    expect(lookupError(refusedWith(429, { minutes: 0 }))).toEqual({ kind: 'tooManyAttempts', minutes: 15 })
  })

  it('reports any other failure as a failed lookup', () => {
    expect(lookupError(refusedWith(500))).toEqual({ kind: 'lookupFailed' })
    expect(lookupError(new Error('boom'))).toEqual({ kind: 'lookupFailed' })
  })
})
