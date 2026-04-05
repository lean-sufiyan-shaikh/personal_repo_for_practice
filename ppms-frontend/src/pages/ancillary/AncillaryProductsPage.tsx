import { useState, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { usePumpStore } from '../../store/usePumpStore'
import { ancillaryApi } from '../../api/ancillaryApi'
import { creditApi } from '../../api/creditApi'
import type {
  AncillaryProduct,
  AncillaryLotDetail,
  CreateProductRequest,
  RecordStockDeliveryRequest,
  RecordAncillarySaleRequest,
  AncillaryPaymentMode,
  UnitOfMeasure,
  UpdateLotRequest,
} from '../../api/ancillaryApi'
import { useAuthStore } from '../../store/authStore'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'
import type { PagedResponse } from '../../types/paged'

// ── Helpers ─────────────────────────────────────────────────────────────────

function fmtCurrency(n: number | null | undefined) {
  if (n == null) return '—'
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function fmtDate(s: string) {
  return new Date(s).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

const PAYMENT_MODES: AncillaryPaymentMode[] = ['CASH', 'UPI', 'CARD', 'FLEET_CARD', 'CREDIT']
const UNITS: UnitOfMeasure[] = ['LITRE', 'KG', 'PIECE']
type Tab = 'products' | 'stockin' | 'sales'

// ── Main page ────────────────────────────────────────────────────────────────

export default function AncillaryProductsPage() {
  const { user } = useAuthStore()
  const qc = useQueryClient()

  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'
  const isManagerOrAbove = isOwnerOrAdmin || user?.role === 'MANAGER'

  const { selectedPumpId } = usePumpStore()
  const [activeTab, setActiveTab] = useState<Tab>('products')

  const pumpId = selectedPumpId

  const { data: products = [], isLoading: productsLoading } = useQuery({
    queryKey: ['ancillaryProducts', pumpId],
    queryFn:  () => ancillaryApi.getProducts(pumpId!),
    enabled:  !!pumpId,
  })


  if (!pumpId && !isOwnerOrAdmin) {
    return (
      <div className="p-6">
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-amber-700 text-sm">
          You are not assigned to any pump. Ask the Owner or Admin to assign you.
        </div>
      </div>
    )
  }

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* ── Page heading ── */}
      <div>
        <h2 className="text-xl font-bold text-slate-800">Products</h2>
        <p className="text-slate-500 text-sm mt-1">
          Manage lubricants, accessories, and other counter products. Track stock and sales.
        </p>
      </div>

      {!pumpId ? (
        <div className="bg-white rounded-xl border border-slate-200 p-8 text-sm text-slate-400 text-center">
          No pump selected. Use the pump selector in the top navigation bar.
        </div>
      ) : (
        <>
          {/* ── Tab bar ── */}
          <div className="flex gap-1 bg-slate-100 rounded-xl p-1 w-fit">
            {(['products', 'stockin', 'sales'] as Tab[]).map(tab => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                  activeTab === tab
                    ? 'bg-white text-slate-800 shadow-sm'
                    : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                {tab === 'products' ? 'Products' : tab === 'stockin' ? 'Stock In' : 'Sales History'}
              </button>
            ))}
          </div>

          {/* ── Tab content ── */}
          {activeTab === 'products' && (
            <ProductsTab
              pumpId={pumpId}
              products={products}
              loading={productsLoading}
              isOwnerOrAdmin={isOwnerOrAdmin}
              isManagerOrAbove={isManagerOrAbove}
              onRefresh={() => qc.invalidateQueries({ queryKey: ['ancillaryProducts', pumpId] })}
            />
          )}
          {activeTab === 'stockin' && (
            <StockInTab
              pumpId={pumpId}
              products={products.filter(p => p.status === 'ACTIVE')}
              isManagerOrAbove={isManagerOrAbove}
              onRefresh={() => qc.invalidateQueries({ queryKey: ['ancillaryProducts', pumpId] })}
            />
          )}
          {activeTab === 'sales' && (
            <SalesTab
              pumpId={pumpId}
            />
          )}
        </>
      )}
    </div>
  )
}

// ── Products Tab ─────────────────────────────────────────────────────────────

function ProductsTab({
  pumpId, products, loading, isOwnerOrAdmin, isManagerOrAbove, onRefresh,
}: {
  pumpId: number
  products: AncillaryProduct[]
  loading: boolean
  isOwnerOrAdmin: boolean
  isManagerOrAbove: boolean
  onRefresh: () => void
}) {
  const qc = useQueryClient()
  const [showAddForm, setShowAddForm] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [productsPage, setProductsPage] = useState(0)
  const [productsPageSize, setProductsPageSize] = useState(10)
  const [sellProduct, setSellProduct] = useState<AncillaryProduct | null>(null)
  const [stockLotsProduct, setStockLotsProduct] = useState<AncillaryProduct | null>(null)

  // Reset to page 0 whenever the search query changes
  useEffect(() => { setProductsPage(0) }, [searchQuery])

  // Add product form state
  const [form, setForm] = useState<CreateProductRequest>({
    name: '', brand: '', variant: '',
    packageSize: 1, unitOfMeasure: 'PIECE',
  })
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: CreateProductRequest) => ancillaryApi.createProduct(pumpId, data),
    onSuccess: () => {
      setShowAddForm(false)
      setFormError(null)
      setForm({ name: '', brand: '', variant: '', packageSize: 1, unitOfMeasure: 'PIECE' })
      onRefresh()
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to create product'),
  })

  const statusMutation = useMutation({
    mutationFn: ({ id, status }: { id: number; status: 'ACTIVE' | 'INACTIVE' }) =>
      ancillaryApi.setProductStatus(pumpId, id, status),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ancillaryProducts', pumpId] }),
  })

  const handleCreate = () => {
    if (!form.name.trim()) { setFormError('Product name is required'); return }
    if (!form.packageSize || form.packageSize <= 0) { setFormError('Package size must be greater than 0'); return }
    createMutation.mutate({
      ...form,
      brand:   form.brand?.trim()   || undefined,
      variant: form.variant?.trim() || undefined,
    })
  }

  const stockBadge = (p: AncillaryProduct) => {
    if (p.lowStockThreshold == null) {
      return <span className="text-xs bg-slate-100 text-slate-500 px-2 py-0.5 rounded-full">{p.currentStockUnits} units</span>
    }
    if (p.currentStockUnits <= p.lowStockThreshold) {
      return <span className="text-xs bg-red-100 text-red-700 px-2 py-0.5 rounded-full font-medium">{p.currentStockUnits} units ⚠️ Low</span>
    }
    return <span className="text-xs bg-emerald-100 text-emerald-700 px-2 py-0.5 rounded-full">{p.currentStockUnits} units</span>
  }

  const q = searchQuery.trim().toLowerCase()
  const filteredProducts = q
    ? products.filter(p =>
        p.displayName.toLowerCase().includes(q) ||
        (p.brand ?? '').toLowerCase().includes(q) ||
        (p.name ?? '').toLowerCase().includes(q)
      )
    : products

  // Build a client-side PagedResponse from the filtered list
  const totalElements = filteredProducts.length
  const totalPages = Math.max(1, Math.ceil(totalElements / productsPageSize))
  const safePage = Math.min(productsPage, totalPages - 1)
  const pagedProducts = filteredProducts.slice(safePage * productsPageSize, (safePage + 1) * productsPageSize)
  const productsPaged: PagedResponse<AncillaryProduct> = {
    content: pagedProducts,
    page: safePage,
    pageSize: productsPageSize,
    totalElements,
    totalPages,
    hasNext: safePage < totalPages - 1,
    hasPrevious: safePage > 0,
  }

  return (
    <div className="space-y-4">
      {/* Top bar: search + add button */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-xs">
          <svg className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
          </svg>
          <input
            type="text"
            placeholder="Search products…"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-400 bg-white"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
            >
              ✕
            </button>
          )}
        </div>
        {isOwnerOrAdmin && (
          <button
            onClick={() => setShowAddForm(v => !v)}
            className="ml-auto bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {showAddForm ? 'Cancel' : '+ Add Product'}
          </button>
        )}
      </div>

      {/* Add Product form */}
      {showAddForm && (
        <div className="bg-white rounded-xl border border-slate-200 p-5 space-y-4">
          <h3 className="text-sm font-semibold text-slate-700">New Product</h3>
          {formError && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{formError}</div>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Product Name *</label>
              <input
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                placeholder="e.g. Engine Oil"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Brand</label>
              <input
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.brand ?? ''}
                onChange={e => setForm(f => ({ ...f, brand: e.target.value }))}
                placeholder="e.g. Castrol"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Variant</label>
              <input
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.variant ?? ''}
                onChange={e => setForm(f => ({ ...f, variant: e.target.value }))}
                placeholder="e.g. GTX 10W-40"
              />
            </div>
            <div className="flex gap-2">
              <div className="flex-1">
                <label className="block text-xs font-medium text-slate-600 mb-1">Package Size *</label>
                <input
                  type="number"
                  min="0.001"
                  step="0.001"
                  className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                  value={form.packageSize}
                  onChange={e => setForm(f => ({ ...f, packageSize: parseFloat(e.target.value) || 0 }))}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1">Unit *</label>
                <SearchableSelect
                  value={form.unitOfMeasure}
                  onChange={v => setForm(f => ({ ...f, unitOfMeasure: v as UnitOfMeasure }))}
                  options={UNITS.map(u => ({ value: u, label: u }))}
                />
              </div>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Low Stock Alert (units)</label>
              <input
                type="number"
                min="0"
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.lowStockThreshold ?? ''}
                onChange={e => setForm(f => ({ ...f, lowStockThreshold: e.target.value ? parseInt(e.target.value) : undefined }))}
                placeholder="Leave blank to disable"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setShowAddForm(false)}
              className="px-4 py-2 text-sm text-slate-600 hover:text-slate-800 border border-slate-200 rounded-lg"
            >
              Cancel
            </button>
            <button
              onClick={handleCreate}
              disabled={createMutation.isPending}
              className="px-4 py-2 text-sm bg-blue-600 hover:bg-blue-700 text-white rounded-lg disabled:opacity-50 transition-colors"
            >
              {createMutation.isPending ? 'Saving…' : 'Create Product'}
            </button>
          </div>
        </div>
      )}

      {/* Product grid */}
      {loading ? (
        <div className="text-sm text-slate-400 text-center py-8">Loading products…</div>
      ) : filteredProducts.length === 0 ? (
        <div className="bg-white rounded-xl border border-slate-200 p-8 text-sm text-slate-400 text-center">
          {searchQuery ? `No products match "${searchQuery}".` : 'No products configured yet. Add your first product above.'}
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {pagedProducts.map(product => (
            <div
              key={product.id}
              className={`bg-white rounded-xl border p-4 space-y-3 ${
                product.status === 'INACTIVE' ? 'border-slate-200 opacity-60' : 'border-slate-200'
              }`}
            >
              {/* Product header */}
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm font-semibold text-slate-800 leading-tight">{product.displayName}</p>
                  <p className="text-xs text-slate-400 mt-0.5">{product.unitOfMeasure} · {product.packageSize}</p>
                </div>
                <span className={`text-xs px-2 py-0.5 rounded-full font-medium shrink-0 ${
                  product.status === 'ACTIVE'
                    ? 'bg-emerald-100 text-emerald-700'
                    : 'bg-slate-100 text-slate-500'
                }`}>
                  {product.status}
                </span>
              </div>

              {/* Stock + buy price row */}
              <div className="flex items-center gap-2 flex-wrap">
                {stockBadge(product)}
                <span className="text-xs text-slate-400">·</span>
                <span className="text-xs text-slate-500">
                  {product.fifoCostPricePerUnit != null
                    ? <span>Buy: <span className="font-medium text-slate-700">{fmtCurrency(product.fifoCostPricePerUnit)}/unit</span></span>
                    : <span className="text-slate-400">No stock lot</span>}
                </span>
              </div>

              {/* Action buttons */}
              <div className="flex items-center gap-2 pt-1 border-t border-slate-100 flex-wrap">
                {/* Sell button — visible to managers and above for active products */}
                {isManagerOrAbove && product.status === 'ACTIVE' && (
                  <button
                    onClick={() => setSellProduct(product)}
                    className="text-xs bg-emerald-600 hover:bg-emerald-700 text-white font-medium px-3 py-1 rounded-md transition-colors"
                  >
                    Sell
                  </button>
                )}
                {/* Stock Lots button — visible to everyone, shows ACTIVE FIFO lots */}
                <button
                  onClick={() => setStockLotsProduct(product)}
                  className="text-xs bg-slate-100 hover:bg-slate-200 text-slate-600 font-medium px-3 py-1 rounded-md transition-colors"
                >
                  Stock Lots
                </button>
                {isOwnerOrAdmin && (
                  <>
                    <span className="text-slate-200">|</span>
                    <button
                      onClick={() => statusMutation.mutate({
                        id: product.id,
                        status: product.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
                      })}
                      disabled={statusMutation.isPending}
                      className={`text-xs font-medium ${
                        product.status === 'ACTIVE'
                          ? 'text-slate-500 hover:text-red-600'
                          : 'text-emerald-600 hover:text-emerald-800'
                      }`}
                    >
                      {product.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
                    </button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Products pagination */}
      {!loading && filteredProducts.length > 0 && (
        <Pagination
          data={productsPaged}
          onPageChange={p => setProductsPage(p)}
          onPageSizeChange={s => { setProductsPageSize(s); setProductsPage(0) }}
          pageSizeOptions={[10, 20, 50]}
        />
      )}

      {/* Sell dialog */}
      {sellProduct && (
        <SellDialog
          pumpId={pumpId}
          product={sellProduct}
          onClose={() => setSellProduct(null)}
          onSuccess={() => {
            setSellProduct(null)
            onRefresh()
            qc.invalidateQueries({ queryKey: ['ancillarySales', pumpId] })
          }}
        />
      )}

      {/* Stock Lots dialog */}
      {stockLotsProduct && (
        <StockLotsDialog
          pumpId={pumpId}
          product={stockLotsProduct}
          isOwnerOrAdmin={isOwnerOrAdmin}
          onClose={() => setStockLotsProduct(null)}
          onUpdated={() => {
            onRefresh()
            qc.invalidateQueries({ queryKey: ['ancillaryLots', pumpId, stockLotsProduct.id] })
          }}
        />
      )}
    </div>
  )
}

// ── Sell Dialog ───────────────────────────────────────────────────────────────
/**
 * Computes the projected total profit for a sale by walking through active FIFO lots
 * in consumption order and computing (sellPrice - costPrice) × consumedQty per batch.
 *
 * This correctly handles multi-batch sales where different lots have different cost prices.
 * Example: lots = [{5 units @ ₹100}, {2 units @ ₹200}], sell 6 @ ₹120
 *   → batch1: (120-100)×5 = 100, batch2: (120-200)×1 = -80 → total = 20
 *   (the old approach of (120-100)×6 = 120 was incorrect)
 *
 * Returns null when lot data is insufficient to cover the requested quantity
 * (e.g. lots were consumed by another sale after the page loaded).
 */
function computeFifoProjectedProfit(
  qty: number,
  sellPricePerUnit: number,
  lots: AncillaryProduct['activeFifoLots'],
): number | null {
  if (!lots || lots.length === 0) return null
  let remaining = qty
  let totalCost = 0
  for (const lot of lots) {
    if (remaining <= 0) break
    const consumed = Math.min(remaining, lot.remainingQuantity)
    totalCost += consumed * lot.costPricePerUnit
    remaining -= consumed
  }
  if (remaining > 0) return null  // insufficient lot data for this quantity
  return qty * sellPricePerUnit - totalCost
}

// 2-step modal: Step 1 = fill form, Step 2 = review & confirm

function SellDialog({
  pumpId, product, onClose, onSuccess,
}: {
  pumpId: number
  product: AncillaryProduct
  onClose: () => void
  onSuccess: () => void
}) {
  const [step, setStep] = useState<'form' | 'review'>('form')
  const [form, setForm] = useState<RecordAncillarySaleRequest>({
    productId: product.id,
    quantityUnits: 1,
    sellingPricePerUnit: product.fifoCostPricePerUnit ?? 0,
    paymentMode: 'CASH',
  })
  const [error, setError] = useState<string | null>(null)

  const { data: creditClients = [] } = useQuery({
    queryKey: ['credit-ledger-all', pumpId],
    queryFn: () => creditApi.getLedgerSummary(pumpId),
    enabled: form.paymentMode === 'CREDIT',
  })

  const saleMutation = useMutation({
    mutationFn: (data: RecordAncillarySaleRequest) => ancillaryApi.recordSale(pumpId, data),
    onSuccess: () => onSuccess(),
    onError: (err: any) => {
      setStep('form')
      setError(err?.response?.data?.message ?? 'Failed to record sale')
    },
  })

  const handleProceedToReview = () => {
    if (!form.quantityUnits || form.quantityUnits < 1) { setError('Quantity must be at least 1'); return }
    if (!form.sellingPricePerUnit || form.sellingPricePerUnit <= 0) { setError('Enter a valid selling price greater than 0'); return }
    if (form.paymentMode === 'CREDIT' && !form.clientId) { setError('Please select a client for CREDIT payment'); return }
    setError(null)
    setStep('review')
  }

  const buyPrice   = product.fifoCostPricePerUnit
  const sellPrice  = form.sellingPricePerUnit > 0 ? form.sellingPricePerUnit : null
  const totalAmount = sellPrice != null && form.quantityUnits > 0
    ? sellPrice * form.quantityUnits
    : null

  // Profit/loss indicator: walks FIFO lots in order so multi-batch sales are correct.
  // e.g. sell 6 from lots [5@100, 2@200] @ 120 → (120-100)×5 + (120-200)×1 = 20, not 120.
  const projectedProfit = (sellPrice != null && form.quantityUnits > 0)
    ? computeFifoProjectedProfit(form.quantityUnits, sellPrice, product.activeFifoLots)
    : null
  const marginBadge = projectedProfit == null ? null
    : projectedProfit > 0 ? { label: `Projected profit: +${fmtCurrency(projectedProfit)}`, cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' }
    : projectedProfit < 0 ? { label: `Projected loss: ${fmtCurrency(projectedProfit)}`,    cls: 'bg-red-50 text-red-700 border-red-200' }
    :                        { label: 'Break-even',                                          cls: 'bg-slate-50 text-slate-600 border-slate-200' }

  const selectedClient = creditClients.find(c => c.id === form.clientId)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-md">

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
          <div>
            <h3 className="text-sm font-semibold text-slate-800">
              {step === 'form' ? 'Record Sale' : 'Review & Confirm'}
            </h3>
            <p className="text-xs text-slate-400 mt-0.5 truncate max-w-[280px]">{product.displayName}</p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-lg leading-none">✕</button>
        </div>

        {/* Step 1 — Form */}
        {step === 'form' && (
          <div className="p-5 space-y-4">
            {error && (
              <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</div>
            )}

            {/* Stock + buy price info row */}
            <div className="bg-slate-50 rounded-lg px-4 py-3 flex items-center justify-between text-sm gap-4">
              <div className="flex items-center gap-3">
                <span className="text-slate-500">Stock</span>
                <span className={`font-medium ${
                  product.currentStockUnits <= (product.lowStockThreshold ?? Infinity) ? 'text-red-600' : 'text-slate-800'
                }`}>{product.currentStockUnits} units</span>
              </div>
              {buyPrice != null && (
                <div className="flex items-center gap-1.5 text-xs text-slate-500">
                  <span>Buy price</span>
                  <span className="font-semibold text-slate-700">{fmtCurrency(buyPrice)}/unit</span>
                </div>
              )}
            </div>

            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-600 mb-1">Quantity (units) *</label>
                  <input
                    type="number" min="1" step="1" autoFocus
                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                    value={form.quantityUnits}
                    onChange={e => setForm(f => ({ ...f, quantityUnits: parseInt(e.target.value) || 0 }))}
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-600 mb-1">Selling Price (₹/unit) *</label>
                  <input
                    type="number" min="0.01" step="0.01"
                    className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                    value={form.sellingPricePerUnit || ''}
                    onChange={e => setForm(f => ({ ...f, sellingPricePerUnit: parseFloat(e.target.value) || 0 }))}
                    placeholder="MRP"
                  />
                </div>
              </div>

              {/* Profit/loss badge — total projected profit across all FIFO batches */}
              {marginBadge && (
                <div className={`rounded-lg px-4 py-2 border text-xs font-medium ${marginBadge.cls}`}>
                  {marginBadge.label}
                </div>
              )}

              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1">Payment Mode *</label>
                <SearchableSelect
                  value={form.paymentMode}
                  onChange={v => setForm(f => ({
                    ...f,
                    paymentMode: v as AncillaryPaymentMode,
                    clientId: v !== 'CREDIT' ? undefined : f.clientId,
                    clientName: v !== 'CREDIT' ? undefined : f.clientName,
                  }))}
                  options={PAYMENT_MODES.map(m => ({ value: m, label: m.replace('_', ' ') }))}
                />
              </div>

              {form.paymentMode === 'CREDIT' && (
                <div>
                  <label className="block text-xs font-medium text-slate-600 mb-1">Client * (required for credit)</label>
                  <SearchableSelect
                    value={form.clientId ? form.clientId.toString() : ''}
                    onChange={v => {
                      const client = creditClients.find(c => c.id === parseInt(v))
                      setForm(f => ({ ...f, clientId: client?.id, clientName: client?.name }))
                    }}
                    placeholder="Search client…"
                    options={creditClients.map(c => ({
                      value: c.id.toString(),
                      label: c.name + (c.phone ? ` · ${c.phone}` : ''),
                    }))}
                  />
                </div>
              )}

              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1">Bill No</label>
                <input
                  className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                  value={form.billNo ?? ''}
                  onChange={e => setForm(f => ({ ...f, billNo: e.target.value || undefined }))}
                  placeholder="Optional"
                />
              </div>
            </div>

            {/* Live total */}
            {totalAmount != null && (
              <div className="bg-blue-50 border border-blue-100 rounded-lg px-4 py-3 flex items-center justify-between">
                <span className="text-sm text-blue-600">Total</span>
                <span className="text-sm font-semibold text-blue-700">{fmtCurrency(totalAmount)}</span>
              </div>
            )}

            <div className="flex justify-end gap-2 pt-1">
              <button onClick={onClose} className="px-4 py-2 text-sm text-slate-600 hover:text-slate-800 border border-slate-200 rounded-lg">
                Cancel
              </button>
              <button
                onClick={handleProceedToReview}
                className="px-5 py-2 text-sm bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors"
              >
                Review →
              </button>
            </div>
          </div>
        )}

        {/* Step 2 — Review */}
        {step === 'review' && (
          <div className="p-5 space-y-4">
            <p className="text-sm text-slate-500">Please review the details before confirming.</p>

            <div className="bg-slate-50 rounded-xl border border-slate-200 divide-y divide-slate-100 text-sm">
              <ReviewRow label="Product" value={product.displayName} />
              <ReviewRow label="Quantity" value={`${form.quantityUnits} unit${form.quantityUnits !== 1 ? 's' : ''}`} />
              {buyPrice != null && <ReviewRow label="Buy price / unit" value={fmtCurrency(buyPrice)} />}
              <ReviewRow label="Sell price / unit" value={fmtCurrency(form.sellingPricePerUnit)} />
              <ReviewRow label="Total" value={fmtCurrency(totalAmount)} highlight />
              {marginBadge && (
                <div className={`flex items-center justify-between px-4 py-2.5 rounded-b-xl ${marginBadge.cls}`}>
                  <span className="text-xs">Margin</span>
                  <span className="text-xs font-semibold">{marginBadge.label}</span>
                </div>
              )}
            </div>

            <div className="bg-slate-50 rounded-xl border border-slate-200 divide-y divide-slate-100 text-sm">
              <ReviewRow label="Payment mode" value={form.paymentMode.replace('_', ' ')} />
              {form.paymentMode === 'CREDIT' && selectedClient && (
                <ReviewRow label="Client" value={selectedClient.name} />
              )}
              {form.billNo && <ReviewRow label="Bill No" value={form.billNo} />}
            </div>

            {error && (
              <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</div>
            )}

            <div className="flex justify-between gap-2 pt-1">
              <button
                onClick={() => { setStep('form'); setError(null) }}
                className="px-4 py-2 text-sm text-slate-600 hover:text-slate-800 border border-slate-200 rounded-lg"
              >
                ← Back
              </button>
              <button
                onClick={() => saleMutation.mutate(form)}
                disabled={saleMutation.isPending}
                className="px-5 py-2 text-sm bg-emerald-600 hover:bg-emerald-700 text-white font-medium rounded-lg disabled:opacity-50 transition-colors"
              >
                {saleMutation.isPending ? 'Recording…' : 'Confirm Sale'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function ReviewRow({ label, value, highlight = false }: { label: string; value: string | null; highlight?: boolean }) {
  return (
    <div className="flex items-center justify-between px-4 py-2.5">
      <span className="text-slate-500 text-xs">{label}</span>
      <span className={`font-medium text-sm ${highlight ? 'text-blue-700' : 'text-slate-800'}`}>{value ?? '—'}</span>
    </div>
  )
}

// ── Stock Lots Dialog ─────────────────────────────────────────────────────────
/**
 * Shows all ACTIVE FIFO lots for a product in consumption order (oldest first).
 * Each row shows: In Date, Bill No, Cost Price/unit, Remaining, Original.
 * Owner/Admin can edit remaining quantity and cost price inline per row.
 * Editing remaining quantity automatically adjusts product.currentStockUnits on the backend.
 */
function StockLotsDialog({
  pumpId, product, isOwnerOrAdmin, onClose, onUpdated,
}: {
  pumpId: number
  product: AncillaryProduct
  isOwnerOrAdmin: boolean
  onClose: () => void
  onUpdated: () => void
}) {
  const qc = useQueryClient()
  const [editingLotId, setEditingLotId] = useState<number | null>(null)
  const [editForm, setEditForm] = useState<{ costPricePerUnit: string; remainingQuantity: string }>({
    costPricePerUnit: '',
    remainingQuantity: '',
  })
  const [editError, setEditError] = useState<string | null>(null)

  const { data: lots = [], isLoading } = useQuery<AncillaryLotDetail[]>({
    queryKey: ['ancillaryLots', pumpId, product.id],
    queryFn:  () => ancillaryApi.getLots(pumpId, product.id),
  })

  const updateMutation = useMutation({
    mutationFn: ({ lotId, data }: { lotId: number; data: UpdateLotRequest }) =>
      ancillaryApi.updateLot(pumpId, lotId, data),
    onSuccess: () => {
      setEditingLotId(null)
      setEditError(null)
      qc.invalidateQueries({ queryKey: ['ancillaryLots', pumpId, product.id] })
      onUpdated()
    },
    onError: (err: any) => setEditError(err?.response?.data?.message ?? 'Failed to update lot'),
  })

  const startEdit = (lot: AncillaryLotDetail) => {
    setEditingLotId(lot.id)
    setEditForm({
      costPricePerUnit: lot.costPricePerUnit.toString(),
      remainingQuantity: lot.remainingQuantity.toString(),
    })
    setEditError(null)
  }

  const handleSave = (lot: AncillaryLotDetail) => {
    const newCost = parseFloat(editForm.costPricePerUnit)
    const newQty  = parseInt(editForm.remainingQuantity, 10)
    if (isNaN(newCost) || newCost <= 0) { setEditError('Cost price must be greater than 0'); return }
    if (isNaN(newQty)  || newQty  <  0) { setEditError('Remaining quantity cannot be negative'); return }
    if (newQty > lot.originalQuantity)  { setEditError(`Remaining quantity cannot exceed original (${lot.originalQuantity})`); return }

    const payload: UpdateLotRequest = {}
    if (newCost !== lot.costPricePerUnit)  payload.costPricePerUnit  = newCost
    if (newQty  !== lot.remainingQuantity) payload.remainingQuantity = newQty
    if (Object.keys(payload).length === 0) { setEditingLotId(null); return }

    updateMutation.mutate({ lotId: lot.id, data: payload })
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div className="bg-white rounded-2xl shadow-xl w-full max-w-2xl max-h-[85vh] flex flex-col">

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 shrink-0">
          <div>
            <h3 className="text-sm font-semibold text-slate-800">Stock Lots</h3>
            <p className="text-xs text-slate-400 mt-0.5 truncate max-w-[380px]">{product.displayName}</p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-lg leading-none">✕</button>
        </div>

        {/* Body */}
        <div className="overflow-auto flex-1 p-5">
          {isLoading ? (
            <p className="text-sm text-slate-400 text-center py-8">Loading lots…</p>
          ) : lots.length === 0 ? (
            <p className="text-sm text-slate-400 text-center py-8">No active stock lots. Record a stock delivery first.</p>
          ) : (
            <>
              {editError && (
                <div className="mb-3 text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{editError}</div>
              )}
              <table className="w-full text-sm border-collapse">
                <thead>
                  <tr className="text-xs text-slate-500 border-b border-slate-200">
                    <th className="text-left pb-2 pr-4 font-medium">In Date</th>
                    <th className="text-left pb-2 pr-4 font-medium">Bill No</th>
                    <th className="text-right pb-2 pr-4 font-medium">Cost Price/Unit</th>
                    <th className="text-right pb-2 pr-4 font-medium">Remaining</th>
                    <th className="text-right pb-2 pr-4 font-medium">Original</th>
                    {isOwnerOrAdmin && <th className="text-right pb-2 font-medium">Actions</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {lots.map((lot, idx) => (
                    <tr key={lot.id} className={idx % 2 === 0 ? 'bg-white' : 'bg-slate-50/50'}>
                      {editingLotId === lot.id ? (
                        <>
                          <td className="py-2.5 pr-4 text-slate-700">{fmtDate(lot.deliveryDate)}</td>
                          <td className="py-2.5 pr-4 text-slate-500">{lot.invoiceReference ?? '—'}</td>
                          <td className="py-2.5 pr-4 text-right">
                            <input
                              type="number"
                              min="0.01"
                              step="0.01"
                              value={editForm.costPricePerUnit}
                              onChange={e => setEditForm(f => ({ ...f, costPricePerUnit: e.target.value }))}
                              className="w-24 border border-blue-300 rounded px-2 py-1 text-sm text-right focus:outline-none focus:ring-2 focus:ring-blue-400"
                            />
                          </td>
                          <td className="py-2.5 pr-4 text-right">
                            <input
                              type="number"
                              min="0"
                              max={lot.originalQuantity}
                              step="1"
                              value={editForm.remainingQuantity}
                              onChange={e => setEditForm(f => ({ ...f, remainingQuantity: e.target.value }))}
                              className="w-20 border border-blue-300 rounded px-2 py-1 text-sm text-right focus:outline-none focus:ring-2 focus:ring-blue-400"
                            />
                          </td>
                          <td className="py-2.5 pr-4 text-right text-slate-500">{lot.originalQuantity}</td>
                          <td className="py-2.5 text-right">
                            <div className="flex items-center justify-end gap-2">
                              <button
                                onClick={() => handleSave(lot)}
                                disabled={updateMutation.isPending}
                                className="text-xs bg-blue-600 hover:bg-blue-700 text-white font-medium px-3 py-1 rounded-md disabled:opacity-50 transition-colors"
                              >
                                {updateMutation.isPending ? 'Saving…' : 'Save'}
                              </button>
                              <button
                                onClick={() => { setEditingLotId(null); setEditError(null) }}
                                className="text-xs text-slate-500 hover:text-slate-700"
                              >
                                Cancel
                              </button>
                            </div>
                          </td>
                        </>
                      ) : (
                        <>
                          <td className="py-2.5 pr-4 text-slate-700">{fmtDate(lot.deliveryDate)}</td>
                          <td className="py-2.5 pr-4 text-slate-500">{lot.invoiceReference ?? '—'}</td>
                          <td className="py-2.5 pr-4 text-right font-medium text-slate-800">{fmtCurrency(lot.costPricePerUnit)}</td>
                          <td className="py-2.5 pr-4 text-right">
                            <span className="font-semibold text-emerald-700">{lot.remainingQuantity}</span>
                          </td>
                          <td className="py-2.5 pr-4 text-right text-slate-500">{lot.originalQuantity}</td>
                          {isOwnerOrAdmin && (
                            <td className="py-2.5 text-right">
                              <button
                                onClick={() => startEdit(lot)}
                                className="text-xs text-blue-600 hover:text-blue-800 font-medium"
                              >
                                Edit
                              </button>
                            </td>
                          )}
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-slate-100 shrink-0 flex justify-between items-center">
          <p className="text-xs text-slate-400">
            Lots shown in FIFO order — oldest consumed first
          </p>
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-slate-600 hover:text-slate-800 border border-slate-200 rounded-lg transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

// ── Stock In Tab ─────────────────────────────────────────────────────────────

function StockInTab({
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

  const { data: deliveriesPage } = useQuery({
    queryKey: ['ancillaryDeliveries', pumpId, deliveryPage, deliveryPageSize],
    queryFn:  () => ancillaryApi.getDeliveries(pumpId, deliveryPage, deliveryPageSize),
  })
  const deliveries = deliveriesPage?.content ?? []
  const today = new Date().toISOString().split('T')[0]
  const [form, setForm] = useState<RecordStockDeliveryRequest & { productId: number | '' }>({
    productId: products[0]?.id ?? '',
    quantityUnits: 1,
    costPricePerUnit: 0,
    deliveryDate: today,
    invoiceReference: '',
  })
  const [error, setError] = useState<string | null>(null)

  const deliveryMutation = useMutation({
    mutationFn: (data: { productId: number; req: RecordStockDeliveryRequest }) =>
      ancillaryApi.recordDelivery(pumpId, data.productId, data.req),
    onSuccess: () => {
      setError(null)
      setForm(f => ({ ...f, quantityUnits: 1, costPricePerUnit: 0, invoiceReference: '' }))
      setDeliveryPage(0)
      qc.invalidateQueries({ queryKey: ['ancillaryDeliveries', pumpId] })
      onRefresh()
    },
    onError: (err: any) => setError(err?.response?.data?.message ?? 'Failed to record delivery'),
  })

  const handleSubmit = () => {
    if (!form.productId) { setError('Select a product'); return }
    if (!form.quantityUnits || form.quantityUnits < 1) { setError('Quantity must be at least 1'); return }
    if (!form.costPricePerUnit || form.costPricePerUnit <= 0) { setError('Enter a valid cost price'); return }
    if (!form.deliveryDate) { setError('Delivery date is required'); return }
    deliveryMutation.mutate({
      productId: form.productId as number,
      req: {
        quantityUnits: form.quantityUnits,
        costPricePerUnit: form.costPricePerUnit,
        deliveryDate: form.deliveryDate,
        invoiceReference: form.invoiceReference || undefined,
      },
    })
  }

  return (
    <div className="space-y-5">
      {/* Stock-in form */}
      {isManagerOrAbove ? (
        <div className="bg-white rounded-xl border border-slate-200 p-5 space-y-4">
          <h3 className="text-sm font-semibold text-slate-700">Record Stock Delivery</h3>
          {error && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</div>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="sm:col-span-2">
              <label className="block text-xs font-medium text-slate-600 mb-1">Product *</label>
              <SearchableSelect
                value={form.productId ? form.productId.toString() : ''}
                onChange={v => setForm(f => ({ ...f, productId: v ? parseInt(v) : '' }))}
                placeholder="Select product…"
                options={products.map(p => ({ value: p.id.toString(), label: `${p.displayName} (stock: ${p.currentStockUnits})` }))}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Quantity (units) *</label>
              <input
                type="number"
                min="1"
                step="1"
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.quantityUnits}
                onChange={e => setForm(f => ({ ...f, quantityUnits: parseInt(e.target.value) || 0 }))}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Cost Price per Unit (₹) *</label>
              <input
                type="number"
                min="0.01"
                step="0.01"
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.costPricePerUnit || ''}
                onChange={e => setForm(f => ({ ...f, costPricePerUnit: parseFloat(e.target.value) || 0 }))}
                placeholder="₹"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Delivery Date *</label>
              <input
                type="date"
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.deliveryDate}
                onChange={e => setForm(f => ({ ...f, deliveryDate: e.target.value }))}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">Invoice Reference</label>
              <input
                className="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
                value={form.invoiceReference ?? ''}
                onChange={e => setForm(f => ({ ...f, invoiceReference: e.target.value }))}
                placeholder="Optional"
              />
            </div>
          </div>
          <div className="flex justify-end">
            <button
              onClick={handleSubmit}
              disabled={deliveryMutation.isPending}
              className="px-5 py-2 bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium rounded-lg disabled:opacity-50 transition-colors"
            >
              {deliveryMutation.isPending ? 'Recording…' : 'Record Delivery'}
            </button>
          </div>
        </div>
      ) : (
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-amber-700 text-sm">
          Only Managers, Admins, and Owners can record deliveries.
        </div>
      )}

      {/* Delivery history */}
      <div className="bg-white rounded-xl border border-slate-200">
        <div className="px-5 py-4 border-b border-slate-100">
          <h3 className="text-sm font-semibold text-slate-700">Recent Deliveries</h3>
        </div>
        {deliveries.length === 0 ? (
          <p className="text-sm text-slate-400 text-center py-6">No deliveries recorded yet.</p>
        ) : (
          <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-400 uppercase border-b border-slate-100">
                  <th className="px-5 py-3 text-left">Date</th>
                  <th className="px-5 py-3 text-left">Product ID</th>
                  <th className="px-5 py-3 text-right">Qty</th>
                  <th className="px-5 py-3 text-right">Cost/Unit</th>
                  <th className="px-5 py-3 text-right">Total Cost</th>
                  <th className="px-5 py-3 text-left">Invoice</th>
                </tr>
              </thead>
              <tbody>
                {deliveries.map(d => (
                  <tr key={d.id} className="border-b border-slate-50 hover:bg-slate-50">
                    <td className="px-5 py-3 text-slate-600">{fmtDate(d.deliveryDate)}</td>
                    <td className="px-5 py-3 text-slate-600">#{d.productId}</td>
                    <td className="px-5 py-3 text-right font-medium">{d.quantityUnits}</td>
                    <td className="px-5 py-3 text-right">{fmtCurrency(d.costPricePerUnit)}</td>
                    <td className="px-5 py-3 text-right font-medium">
                      {fmtCurrency(d.costPricePerUnit * d.quantityUnits)}
                    </td>
                    <td className="px-5 py-3 text-slate-400">{d.invoiceReference ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
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

// ── Sales History Tab ─────────────────────────────────────────────────────────

function SalesTab({
  pumpId,
}: {
  pumpId: number
}) {
  const qc = useQueryClient()
  const [salesPage, setSalesPage] = useState(0)
  const [salesPageSize, setSalesPageSize] = useState(10)

  const { data: salesPageData } = useQuery({
    queryKey: ['ancillarySales', pumpId, salesPage, salesPageSize],
    queryFn:  () => ancillaryApi.getSales(pumpId, salesPage, salesPageSize),
  })
  const sales = salesPageData?.content ?? []

  const modeColor: Record<string, string> = {
    CASH: 'bg-emerald-100 text-emerald-700',
    UPI: 'bg-blue-100 text-blue-700',
    CARD: 'bg-purple-100 text-purple-700',
    FLEET_CARD: 'bg-amber-100 text-amber-700',
    CREDIT: 'bg-red-100 text-red-700',
  }

  return (
    <div className="bg-white rounded-xl border border-slate-200">
      <div className="px-5 py-4 border-b border-slate-100">
        <h3 className="text-sm font-semibold text-slate-700">Sales History</h3>
      </div>
      {sales.length === 0 ? (
        <p className="text-sm text-slate-400 text-center py-6">No sales recorded yet.</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-400 uppercase border-b border-slate-100">
                  <th className="px-5 py-3 text-left">Date</th>
                  <th className="px-5 py-3 text-left">Product</th>
                  <th className="px-5 py-3 text-right">Qty</th>
                  <th className="px-5 py-3 text-right">Price/Unit</th>
                  <th className="px-5 py-3 text-right">Total</th>
                  <th className="px-5 py-3 text-left">Mode</th>
                  <th className="px-5 py-3 text-left">Client</th>
                </tr>
              </thead>
              <tbody>
                {sales.map(s => (
                  <tr key={s.id} className="border-b border-slate-50 hover:bg-slate-50">
                    <td className="px-5 py-3 text-slate-600 whitespace-nowrap">{fmtDate(s.saleDate)}</td>
                    <td className="px-5 py-3 text-slate-700 font-medium max-w-[180px] truncate">{s.productDisplayName}</td>
                    <td className="px-5 py-3 text-right">{s.quantityUnits}</td>
                    <td className="px-5 py-3 text-right text-slate-500">{fmtCurrency(s.sellingPricePerUnit)}</td>
                    <td className="px-5 py-3 text-right font-semibold text-slate-800">{fmtCurrency(s.totalAmount)}</td>
                    <td className="px-5 py-3">
                      <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${modeColor[s.paymentMode] ?? 'bg-slate-100 text-slate-600'}`}>
                        {s.paymentMode.replace('_', ' ')}
                      </span>
                    </td>
                    <td className="px-5 py-3 text-slate-400">{s.clientName ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {salesPageData && (
            <div className="px-5">
              <Pagination
                data={salesPageData}
                onPageChange={p => { setSalesPage(p); qc.invalidateQueries({ queryKey: ['ancillarySales', pumpId] }) }}
                onPageSizeChange={s => { setSalesPageSize(s); setSalesPage(0) }}
              />
            </div>
          )}
        </>
      )}
    </div>
  )
}
