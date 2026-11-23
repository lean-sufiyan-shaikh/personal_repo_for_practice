import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { History } from 'lucide-react'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct, AncillarySale } from '../../api/ancillaryApi'
import { Pagination } from '../../components/Pagination'
import { EmptyState } from '../../components/EmptyState'
import { BackfillSaleModal } from './BackfillSaleModal'
import { fmtDate, paymentModeBadge } from './ancillaryFormat'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'

// ── Sales History Tab ─────────────────────────────────────────────────────────

export function SalesTab({
  pumpId,
  products,
  isOwnerOrAdmin,
  onRefresh,
}: {
  pumpId: number
  products: AncillaryProduct[]
  isOwnerOrAdmin: boolean
  onRefresh: () => void
}) {
  const qc = useQueryClient()
  const [salesPage, setSalesPage] = useState(0)
  const [salesPageSize, setSalesPageSize] = useState(10)
  const [backfillOpen, setBackfillOpen] = useState(false)

  const { data: salesPageData } = useQuery({
    queryKey: ['ancillarySales', pumpId, salesPage, salesPageSize],
    queryFn:  () => ancillaryApi.getSales(pumpId, salesPage, salesPageSize),
  })
  const sales = salesPageData?.content ?? []

  const columns: DataTableColumn<AncillarySale>[] = [
    { key: 'date', header: 'Date', className: 'px-5 text-ink-2 whitespace-nowrap', cell: (sale) => fmtDate(sale.saleDate) },
    { key: 'product', header: 'Product', className: 'px-5 text-ink font-medium max-w-[180px] truncate', cell: (sale) => sale.productDisplayName },
    { key: 'qty', header: 'Qty', numeric: true, className: 'px-5', cell: (sale) => sale.quantityUnits },
    { key: 'pricePerUnit', header: 'Price/Unit', numeric: true, className: 'px-5 text-ink-3', cell: (sale) => <MoneyText value={sale.sellingPricePerUnit} /> },
    { key: 'total', header: 'Total', numeric: true, className: 'px-5 font-semibold text-ink', cell: (sale) => <MoneyText value={sale.totalAmount} /> },
    { key: 'mode', header: 'Mode', className: 'px-5', cell: (sale) => <StatusBadge {...paymentModeBadge(sale.paymentMode)} /> },
    { key: 'client', header: 'Client', className: 'px-5 text-ink-3', cell: (sale) => sale.clientName ?? '—' },
    { key: 'type', header: 'Type', className: 'px-5', cell: (sale) => sale.isBackfilled && <StatusBadge tone="info" icon={History} label="Backfilled" /> },
  ]

  return (
    <div className="space-y-4">
      {/* Backfill button — Admin/Owner only */}
      {isOwnerOrAdmin && (
        <div className="flex justify-end">
          <button
            onClick={() => setBackfillOpen(true)}
            className="ui-btn ui-btn-secondary"
          >
            Backfill Sale
          </button>
        </div>
      )}

      <div className="ui-card p-0">
        <div className="px-5 py-4 border-b border-border">
          <h3 className="text-sm font-semibold text-ink">Sales History</h3>
        </div>
        {sales.length === 0 ? (
          <EmptyState icon="transactions" title="No sales recorded yet" subtitle="Sales will appear here after you record them." />
        ) : (
          <>
            <DataTable
              caption="Product sales"
              captionHidden
              columns={columns}
              rows={sales}
              rowKey={(sale) => sale.id}
              className="rounded-none border-0"
            />
            {salesPageData && (
              <div className="px-5">
                <Pagination
                  data={salesPageData}
                  onPageChange={setSalesPage}
                  onPageSizeChange={s => { setSalesPageSize(s); setSalesPage(0) }}
                />
              </div>
            )}
          </>
        )}
      </div>

      {backfillOpen && (
        <BackfillSaleModal
          pumpId={pumpId}
          products={products.filter(p => p.status === 'ACTIVE')}
          onClose={() => setBackfillOpen(false)}
          onSuccess={() => {
            setBackfillOpen(false)
            setSalesPage(0)
            qc.invalidateQueries({ queryKey: ['ancillarySales', pumpId] })
            qc.invalidateQueries({ queryKey: ['ancillaryProducts', pumpId] })
            onRefresh()
          }}
        />
      )}
    </div>
  )
}
