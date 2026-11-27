import client from './client'

export interface GstTaxBucket {
  taxableValue: number
  cgst: number
  sgst: number
  igst: number
}

export interface GstPurchaseLine {
  deliveryId: number
  invoiceDate: string
  invoiceNumber: string | null
  supplierName: string
  supplierGstin: string
  productName: string | null
  taxableValue: number
  gstRatePercent: number
  cgst: number
  sgst: number
  igst: number
  total: number
}

/** netTax is output less input per head; a negative figure is credit carried forward. */
export interface GstSummary {
  month: string
  from: string
  to: string
  pumpGstin: string | null
  outwardTaxable: GstTaxBucket
  outwardFromInvoices: GstTaxBucket
  outwardFromCounterSales: GstTaxBucket
  /** GST credit notes issued in the month; already taken off outwardTaxable. */
  outwardCreditNotes?: GstTaxBucket
  nonGstOutwardFuel: number
  inputTax: GstTaxBucket
  netTax: GstTaxBucket
  counterSalesWithoutGst: number
  purchases: GstPurchaseLine[]
}

export const gstSummaryApi = {
  getSummary: (pumpId: number, month: string) =>
    client.get<GstSummary>(`/pumps/${pumpId}/reports/gst-summary`, { params: { month } }).then(r => r.data),
}

export function totalTaxOf(bucket: GstTaxBucket): number {
  return bucket.cgst + bucket.sgst + bucket.igst
}
