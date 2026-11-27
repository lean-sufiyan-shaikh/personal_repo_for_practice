import { describe, expect, it } from 'vitest'
import { emptyPurchaseGstForm, missingPurchaseGstFields, splitPurchaseGst, toPurchaseGstRequest } from './purchaseGst'

describe('splitPurchaseGst', () => {
  it('splits GST into CGST and SGST for a supplier in the pump state, CGST taking the odd paisa', () => {
    expect(splitPurchaseGst(100.05, 18, '27ABCDE1234F1Z5', '27PQRSX6789K1Z2')).toEqual({
      sameState: true, cgst: 9.01, sgst: 9, igst: 0, total: 118.06,
    })
  })

  it('charges IGST for a supplier registered in another state', () => {
    expect(splitPurchaseGst(1000, 12, '29ABCDE1234F1Z5', '27PQRSX6789K1Z2')).toEqual({
      sameState: false, cgst: 0, sgst: 0, igst: 120, total: 1120,
    })
  })
})

describe('missingPurchaseGstFields', () => {
  it('needs nothing while the bill has no GST', () => {
    expect(missingPurchaseGstFields(emptyPurchaseGstForm('2026-11-27'), 0, '')).toEqual([])
  })

  it('needs a valid GSTIN, the supplier, a taxable value and the bill number', () => {
    const form = { ...emptyPurchaseGstForm('2026-11-27'), enabled: true, supplierGstin: '27ABCDE1234' }
    expect(missingPurchaseGstFields(form, 0, ' ')).toEqual(['supplierName', 'supplierGstin', 'taxableValue', 'invoiceNumber'])

    const complete = { ...form, supplierName: 'Castrol Dist', supplierGstin: '27abcde1234f1z5' }
    expect(missingPurchaseGstFields(complete, 500, 'B-1')).toEqual([])
    expect(toPurchaseGstRequest(complete, 500)).toEqual({
      supplierName: 'Castrol Dist', supplierGstin: '27ABCDE1234F1Z5', invoiceDate: '2026-11-27', taxableValue: 500, gstRatePercent: 18,
    })
  })
})
