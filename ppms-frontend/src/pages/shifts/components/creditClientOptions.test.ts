import { describe, expect, it } from 'vitest'
import type { CreditClientSummary } from '../../../api/pumpApi'
import { toClientOption } from './creditClientOptions'

const client = { id: 4, pumpId: 3, name: 'Acme', phone: null, parentClientId: null, isActive: true } as CreditClientSummary

describe('toClientOption', () => {
  it('refuses a mid-shift sale to a client whose credit is on hold', () => {
    const option = toClientOption(client, { clientId: 4, creditStatus: 'ON_HOLD', availableCredit: null }, true)

    expect(option.blockReason).toBe('Credit on hold: long overdue')
  })

  it('still lets credit already given be recorded at shift close, as for an overdue client', () => {
    const option = toClientOption(client, { clientId: 4, creditStatus: 'ON_HOLD', availableCredit: null }, false)

    expect(option.blockReason).toBeNull()
  })
})
