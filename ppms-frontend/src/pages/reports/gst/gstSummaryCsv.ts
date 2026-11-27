import type { GstSummary, GstTaxBucket } from '../../../api/gstSummaryApi'
import type { CsvColumn } from '../../../utils/csv'

export interface GstCsvRow {
  section: string
  label: string
  date?: string
  invoiceNumber?: string | null
  supplierGstin?: string
  taxableValue?: number
  rate?: number
  cgst?: number
  sgst?: number
  igst?: number
  total?: number
}

export const GST_CSV_COLUMNS: CsvColumn<GstCsvRow>[] = [
  { header: 'Section', value: (row) => row.section },
  { header: 'Description', value: (row) => row.label },
  { header: 'Invoice date', value: (row) => row.date, kind: 'date' },
  { header: 'Invoice number', value: (row) => row.invoiceNumber },
  { header: 'Supplier GSTIN', value: (row) => row.supplierGstin },
  { header: 'Taxable value', value: (row) => row.taxableValue, kind: 'money' },
  { header: 'GST rate %', value: (row) => row.rate, kind: 'number' },
  { header: 'CGST', value: (row) => row.cgst, kind: 'money' },
  { header: 'SGST', value: (row) => row.sgst, kind: 'money' },
  { header: 'IGST', value: (row) => row.igst, kind: 'money' },
  { header: 'Total', value: (row) => row.total, kind: 'money' },
]

function bucketRow(section: string, label: string, bucket: GstTaxBucket): GstCsvRow {
  return { section, label, taxableValue: bucket.taxableValue, cgst: bucket.cgst, sgst: bucket.sgst, igst: bucket.igst }
}

/** The GSTR-3B style buckets, then every purchase bill of the input register. For an accountant to verify. */
export function gstCsvRows(summary: GstSummary): GstCsvRow[] {
  return [
    bucketRow('3.1(a)', 'Outward taxable supplies (counter products)', summary.outwardTaxable),
    bucketRow('3.1(a)', 'of which on credit invoices issued', summary.outwardFromInvoices),
    bucketRow('3.1(a)', 'of which counter sales paid on the spot', summary.outwardFromCounterSales),
    ...(summary.outwardCreditNotes
      ? [bucketRow('3.1(a)', 'less GST credit notes issued', summary.outwardCreditNotes)] : []),
    { section: '3.1(e)', label: 'Non-GST outward supplies (petrol and diesel)', taxableValue: summary.nonGstOutwardFuel },
    bucketRow('4(A)(5)', 'Input tax credit (counter-product purchases)', summary.inputTax),
    { ...bucketRow('Net', 'Output tax less input tax (negative = credit carried forward)', summary.netTax), taxableValue: undefined },
    ...summary.purchases.map((line) => ({
      section: 'Input register',
      label: [line.supplierName, line.productName].filter(Boolean).join(' – '),
      date: line.invoiceDate,
      invoiceNumber: line.invoiceNumber,
      supplierGstin: line.supplierGstin,
      taxableValue: line.taxableValue,
      rate: line.gstRatePercent,
      cgst: line.cgst,
      sgst: line.sgst,
      igst: line.igst,
      total: line.total,
    })),
  ]
}
