import { useTranslation } from 'react-i18next'
import type { GstPurchaseLine } from '../../../api/gstSummaryApi'
import { MoneyText } from '../../../components/AmountText'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { formatDecimal } from '../../../utils/format'
import { fmtShortDate } from '../reportFormat'

/** The month's counter-product purchase bills with GST, the register behind the input tax credit. */
export function GstPurchaseRegister({ purchases }: { purchases: GstPurchaseLine[] }) {
  const { t } = useTranslation('reports')
  if (purchases.length === 0) return <p className="ui-empty py-6">{t('gst.noPurchases')}</p>
  const columns: DataTableColumn<GstPurchaseLine>[] = [
    { key: 'date', header: t('gst.columns.invoiceDate'), cell: (line) => fmtShortDate(line.invoiceDate) },
    { key: 'invoice', header: t('gst.columns.invoiceNumber'), cell: (line) => line.invoiceNumber ?? '—' },
    {
      key: 'supplier', header: t('gst.columns.supplier'),
      cell: (line) => (
        <span className="flex flex-col">
          <span className="text-ink">{line.supplierName}</span>
          <span className="text-xs text-ink-3">{line.supplierGstin}</span>
        </span>
      ),
    },
    { key: 'product', header: t('gst.columns.product'), cell: (line) => line.productName ?? '—' },
    { key: 'taxable', header: t('gst.columns.taxableValue'), numeric: true, cell: (line) => <MoneyText value={line.taxableValue} /> },
    { key: 'rate', header: t('gst.columns.rate'), numeric: true, cell: (line) => t('gst.ratePercent', { rate: formatDecimal(line.gstRatePercent, 0) }) },
    { key: 'cgst', header: t('gst.columns.cgst'), numeric: true, cell: (line) => <MoneyText value={line.cgst} /> },
    { key: 'sgst', header: t('gst.columns.sgst'), numeric: true, cell: (line) => <MoneyText value={line.sgst} /> },
    { key: 'igst', header: t('gst.columns.igst'), numeric: true, cell: (line) => <MoneyText value={line.igst} /> },
    { key: 'total', header: t('gst.columns.total'), numeric: true, cell: (line) => <MoneyText value={line.total} className="font-medium" /> },
  ]
  return (
    <DataTable caption={t('gst.registerCaption')} captionHidden columns={columns} rows={purchases}
      rowKey={(line) => line.deliveryId} scroll className="rounded-none border-0" />
  )
}
