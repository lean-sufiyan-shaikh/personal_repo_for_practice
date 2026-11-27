import { useTranslation } from 'react-i18next'
import type { GstSummary, GstTaxBucket } from '../../../api/gstSummaryApi'
import { MoneyText } from '../../../components/AmountText'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'

interface BucketRow {
  key: string
  section: string
  label: string
  bucket: GstTaxBucket | null
  value?: number
}

/** The month's GSTR-3B style buckets: outward taxable, non-GST fuel, input tax credit and the net. */
export function GstBucketsTable({ summary }: { summary: GstSummary }) {
  const { t } = useTranslation('reports')
  const rows: BucketRow[] = [
    { key: 'outward', section: '3.1(a)', label: t('gst.buckets.outward'), bucket: summary.outwardTaxable },
    { key: 'invoices', section: '', label: t('gst.buckets.fromInvoices'), bucket: summary.outwardFromInvoices },
    { key: 'counter', section: '', label: t('gst.buckets.fromCounterSales'), bucket: summary.outwardFromCounterSales },
    ...(summary.outwardCreditNotes ? [{ key: 'creditNotes', section: '', label: t('gst.buckets.lessCreditNotes'),
      bucket: summary.outwardCreditNotes }] : []),
    { key: 'fuel', section: '3.1(e)', label: t('gst.buckets.nonGstFuel'), bucket: null, value: summary.nonGstOutwardFuel },
    { key: 'input', section: '4(A)(5)', label: t('gst.buckets.input'), bucket: summary.inputTax },
    { key: 'net', section: '', label: t('gst.buckets.net'), bucket: summary.netTax },
  ]
  const money = (value: number | undefined) => value === undefined ? '—' : <MoneyText value={value} />
  const columns: DataTableColumn<BucketRow>[] = [
    { key: 'section', header: t('gst.columns.section'), cell: (row) => <span className="text-ink-3">{row.section}</span> },
    { key: 'label', header: t('gst.columns.description'), cell: (row) => <span className={row.key === 'net' ? 'font-semibold text-ink' : 'text-ink'}>{row.label}</span> },
    { key: 'taxable', header: t('gst.columns.taxableValue'), numeric: true,
      cell: (row) => row.key === 'net' ? '—' : money(row.bucket ? row.bucket.taxableValue : row.value) },
    { key: 'cgst', header: t('gst.columns.cgst'), numeric: true, cell: (row) => money(row.bucket?.cgst) },
    { key: 'sgst', header: t('gst.columns.sgst'), numeric: true, cell: (row) => money(row.bucket?.sgst) },
    { key: 'igst', header: t('gst.columns.igst'), numeric: true, cell: (row) => money(row.bucket?.igst) },
  ]
  return (
    <DataTable caption={t('gst.bucketsCaption')} columns={columns} rows={rows} rowKey={(row) => row.key} scroll />
  )
}
