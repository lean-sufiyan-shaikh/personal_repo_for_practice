import { useAuthStore } from '../../../store/authStore'
import type { ProductSalesSummary } from '../../../api/balanceSheetApi'
import { Td } from '../bsTableCells'
import { bsColumn } from '../bsFormat'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { MoneyText } from '../../../components/AmountText'

type ProductLine = ProductSalesSummary['productLines'][number]

function productColumns(isOwnerOrAdmin: boolean): DataTableColumn<ProductLine>[] {
  return [
    bsColumn('product', 'Product'),
    bsColumn('units', 'Units Sold', true),
    bsColumn('revenue', 'Revenue', true),
    ...(isOwnerOrAdmin ? [bsColumn<ProductLine>('cogs', 'COGS', true), bsColumn<ProductLine>('profit', 'Gross Profit', true)] : []),
  ]
}

// ── Ancillary product sales section (DAY reports only) ─────────────────────────

export function ProductSalesSection({ productSales }: { productSales: ProductSalesSummary }) {
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  return (
    <section>
      <h3 className="text-xs font-semibold text-ink-3 uppercase tracking-wider mb-3">
        Ancillary Product Sales
        <span className="ml-2 text-ink-3 normal-case font-normal">
          ({productSales.productLines.length} product{productSales.productLines.length !== 1 ? 's' : ''})
        </span>
      </h3>
      <DataTable
        caption="Ancillary product sales"
        captionHidden
        regionLabel="Product sales"
        columns={productColumns(isOwnerOrAdmin)}
        rows={productSales.productLines}
        rowKey={(line) => line.productId}
        renderRow={(line) => {
          const lineProfit = line.revenue - line.cogs
          return (
            <tr>
              <Td><span className="font-medium text-ink">{line.productName}</span></Td>
              <Td right>{line.unitsSold}</Td>
              <Td right><MoneyText value={line.revenue} /></Td>
              {isOwnerOrAdmin && <Td right><MoneyText value={line.cogs} /></Td>}
              {isOwnerOrAdmin && <Td right>
                <MoneyText value={lineProfit} tone={lineProfit >= 0 ? 'ok' : 'bad'} className="font-medium" />
              </Td>}
            </tr>
          )
        }}
        footer={
          <tr className="bg-surface-sunken">
            <Td>Total</Td>
            <Td right>—</Td>
            <Td right><MoneyText value={productSales.totalRevenue} /></Td>
            {isOwnerOrAdmin && <Td right><MoneyText value={productSales.totalCogs} /></Td>}
            {isOwnerOrAdmin && <Td right>
              <MoneyText value={productSales.grossProfit} tone={productSales.grossProfit >= 0 ? 'ok' : 'bad'} />
            </Td>}
          </tr>
        }
      />
    </section>
  )
}
