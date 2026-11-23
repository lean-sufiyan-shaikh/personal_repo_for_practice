import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { History } from 'lucide-react'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryStockDelivery, AncillaryProduct } from '../../api/ancillaryApi'
import { Pagination } from '../../components/Pagination'
import { EmptyState } from '../../components/EmptyState'
import { StockInDialog } from './StockInDialog'
import { fmtDate } from './ancillaryFormat'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'

// ── Stock In Tab ─────────────────────────────────────────────────────────────

export function StockInTab({
  pumpId, products, isManagerOrAbove, onRefresh,
}: {
  pumpId: number
  products: AncillaryProduct[]
  isManagerOrAbove: boolean
  onRefresh: () => void
}) {
  const qc = useQueryClient()
  const [deliveryPage, setDeliveryPage] = useState(0)
  const [deliveryPageSize, setDeliveryPageSize] = useState(10)
  const [stockInOpen, setStockInOpen] = useState(false)
  const productMap = new Map(products.map((product) => [product.id, product]))

  const { data: deliveriesPage } = useQuery({
    queryKey: ['ancillaryDeliveries', pumpId, deliveryPage, deliveryPageSize],
    queryFn:  () => ancillaryApi.getDeliveries(pumpId, deliveryPage, deliveryPageSize),
  })
  const deliveries = deliveriesPage?.content ?? []

  const columns: DataTableColumn<AncillaryStockDelivery>[] = [
    { key: 'date', header: 'Date', className: 'px-5 text-ink-2', cell: (d) => fmtDate(d.deliveryDate) },
    { key: 'product', header: 'Product', className: 'px-5', cell: (d) => (
      <div className="flex flex-col">
        <span className="font-medium text-ink">{productMap.get(d.productId)?.displayName ?? `Product #${d.productId}`}</span>
        <span className="text-xs text-ink-3">#{d.productId}</span>
      </div>
    ) },
    { key: 'qty', header: 'Qty', numeric: true, className: 'px-5 font-medium', cell: (d) => d.quantityUnits },
    { key: 'costPerUnit', header: 'Cost/Unit', numeric: true, className: 'px-5', cell: (d) => <MoneyText value={d.costPricePerUnit} /> },
    { key: 'totalCost', header: 'Total Cost', numeric: true, className: 'px-5 font-medium', cell: (d) => <MoneyText value={d.costPricePerUnit * d.quantityUnits} /> },
    { key: 'invoice', header: 'Invoice', className: 'px-5 text-ink-3', cell: (d) => d.invoiceReference ?? '—' },
    { key: 'type', header: 'Type', className: 'px-5', cell: (d) => d.isBackfilled && <StatusBadge tone="info" icon={History} label="Backfilled" /> },
  ]

  return (
    <div className="space-y-5">
      {/* Stock-in form */}
      {isManagerOrAbove ? (
        <div className="flex justify-end">
          <button
            onClick={() => setStockInOpen(true)}
            className="ui-btn ui-btn-primary"
          >
            Record Stock Delivery
          </button>
        </div>
      ) : (
        <div className="ui-alert ui-alert-warning text-sm">
          Only Managers, Admins, and Owners can record deliveries.
        </div>
      )}

      {stockInOpen && (
        <StockInDialog
          pumpId={pumpId}
          products={products}
          onClose={() => setStockInOpen(false)}
          onSuccess={() => {
            setStockInOpen(false)
            setDeliveryPage(0)
            qc.invalidateQueries({ queryKey: ['ancillaryDeliveries', pumpId] })
            onRefresh()
          }}
        />
      )}

      {/* Delivery history */}
      <div className="ui-card p-0">
        <div className="px-5 py-4 border-b border-border">
          <h3 className="text-sm font-semibold text-ink">Recent Deliveries</h3>
        </div>
        {deliveries.length === 0 ? (
          <EmptyState icon="generic" title="No deliveries recorded yet" subtitle="Record stock deliveries using the button above." />
        ) : (
          <>
          <DataTable
            caption="Stock-in history"
            captionHidden
            columns={columns}
            rows={deliveries}
            rowKey={(d) => d.id}
            className="rounded-none border-0"
          />
          {deliveriesPage && (
            <div className="px-5">
              <Pagination
                data={deliveriesPage}
                onPageChange={p => setDeliveryPage(p)}
                onPageSizeChange={s => { setDeliveryPageSize(s); setDeliveryPage(0) }}
                pageSizeOptions={[10, 20, 50]}
              />
            </div>
          )}
          </>
        )}
      </div>
    </div>
  )
}
