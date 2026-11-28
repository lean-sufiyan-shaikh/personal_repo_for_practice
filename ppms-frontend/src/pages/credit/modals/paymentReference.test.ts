import { describe, expect, it } from 'vitest'
import { needsReference, referenceFieldsOf } from './paymentReference'

describe('needsReference', () => {
  it('asks for a reference for UPI, bank transfer and cheque only', () => {
    expect(needsReference('UPI')).toBe(true)
    expect(needsReference('BANK_TRANSFER')).toBe(true)
    expect(needsReference('CHEQUE')).toBe(true)
    expect(needsReference('CASH')).toBe(false)
  })
})

describe('referenceFieldsOf', () => {
  const typed = { referenceNo: ' 000123 ', chequeBank: ' HDFC ', chequeDate: '2026-12-05' }

  it('sends the trimmed cheque number, bank and date for a cheque', () => {
    expect(referenceFieldsOf('CHEQUE', typed)).toEqual({ referenceNo: '000123', chequeBank: 'HDFC', chequeDate: '2026-12-05' })
  })

  it('sends only the reference for other modes and leaves blanks out', () => {
    expect(referenceFieldsOf('UPI', typed)).toEqual({ referenceNo: '000123', chequeBank: undefined, chequeDate: undefined })
    expect(referenceFieldsOf('CHEQUE', { referenceNo: ' ', chequeBank: '', chequeDate: '' }))
      .toEqual({ referenceNo: undefined, chequeBank: undefined, chequeDate: undefined })
  })
})
