import type { PurchaseGstRequest } from '../../api/ancillaryApi'
import { isValidGstin, normaliseGstText } from '../../utils/gst'
import { roundToPaise } from '../../utils/money'

export const PURCHASE_GST_RATES = [0, 5, 12, 18, 28] as const

export interface PurchaseGstForm {
  enabled: boolean
  supplierName: string
  supplierGstin: string
  invoiceDate: string
  taxableValue: number | ''
  gstRatePercent: number
}

export interface PurchaseGstSplit {
  sameState: boolean
  cgst: number
  sgst: number
  igst: number
  total: number
}

export function emptyPurchaseGstForm(invoiceDate: string): PurchaseGstForm {
  return { enabled: false, supplierName: '', supplierGstin: '', invoiceDate, taxableValue: '', gstRatePercent: 18 }
}

/** CGST takes the odd paisa, as on the server, so CGST + SGST equals the GST charged. */
export function splitPurchaseGst(taxableValue: number, ratePercent: number, supplierGstin: string,
  pumpGstin: string | null | undefined): PurchaseGstSplit {
  const gst = roundToPaise(taxableValue * ratePercent / 100)
  const sameState = !!pumpGstin && normaliseGstText(supplierGstin).slice(0, 2) === normaliseGstText(pumpGstin).slice(0, 2)
  if (!sameState) return { sameState, cgst: 0, sgst: 0, igst: gst, total: roundToPaise(taxableValue + gst) }
  const cgst = roundToPaise(gst / 2)
  return { sameState, cgst, sgst: roundToPaise(gst - cgst), igst: 0, total: roundToPaise(taxableValue + gst) }
}

export function taxableValueOf(form: PurchaseGstForm, totalCost: number | null): number {
  return form.taxableValue === '' ? totalCost ?? 0 : form.taxableValue
}

export type PurchaseGstNeed = 'supplierName' | 'supplierGstin' | 'invoiceDate' | 'taxableValue' | 'invoiceNumber'

export function missingPurchaseGstFields(form: PurchaseGstForm, taxableValue: number, invoiceNumber: string): PurchaseGstNeed[] {
  if (!form.enabled) return []
  const missing: PurchaseGstNeed[] = []
  if (!form.supplierName.trim()) missing.push('supplierName')
  if (!form.supplierGstin.trim() || !isValidGstin(form.supplierGstin)) missing.push('supplierGstin')
  if (!form.invoiceDate) missing.push('invoiceDate')
  if (!(taxableValue > 0)) missing.push('taxableValue')
  if (!invoiceNumber.trim()) missing.push('invoiceNumber')
  return missing
}

export function toPurchaseGstRequest(form: PurchaseGstForm, taxableValue: number): PurchaseGstRequest | undefined {
  if (!form.enabled) return undefined
  return {
    supplierName: form.supplierName.trim(),
    supplierGstin: normaliseGstText(form.supplierGstin),
    invoiceDate: form.invoiceDate,
    taxableValue: roundToPaise(taxableValue),
    gstRatePercent: form.gstRatePercent,
  }
}
