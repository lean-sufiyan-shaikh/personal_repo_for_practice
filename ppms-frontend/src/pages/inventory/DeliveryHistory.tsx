import { useState, useEffect } from 'react'
import { codeLabel } from '../../constants/labels'
import { withToggled } from '../../utils/setToggle'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, Trash2, Truck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { formatNumberIn } from '../../i18n/format'
import { inventoryApi } from '../../api/inventoryApi'
import type { TankStock, TankerDelivery } from '../../api/inventoryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'
import { parseApiError } from '../../utils/apiError'
import { IconButton } from '../../components/IconButton'
import { LitresText, MoneyText } from '../../components/AmountText'
import { DeleteInvoiceModal } from './DeleteInvoiceModal'
import { DeliveryItem } from './DeliveryItem'
import { fmtDate } from './inventoryFormat'

interface Props {
  pumpId: number
  tanks: TankStock[]
  isOwnerOrAdmin: boolean
  canDeleteInvoice: boolean
}

const NO_INVOICE_KEY = 'No Invoice'

export function DeliveryHistory({ pumpId, tanks, isOwnerOrAdmin, canDeleteInvoice }: Props) {
  const { t } = useTranslation(['inventory', 'common'])
  const queryClient = useQueryClient()
  const [deliveryHistOpen,  setDeliveryHistOpen]  = useState(false)
  const [deliveryPage,      setDeliveryPage]      = useState(0)
  const [deliveryPageSize,  setDeliveryPageSize]  = useState(10)
  const [deliveryTankFilter, setDeliveryTankFilter] = useState('')
  const [deliveryFuelFilter, setDeliveryFuelFilter] = useState('')
  const [collapsedDeliveries,    setCollapsedDeliveries]    = useState<Set<string>>(new Set())
  const [deleteInvoice,          setDeleteInvoice]          = useState<{ invoice: string; rows: TankerDelivery[] } | null>(null)

  const deleteLatestMutation = useMutation({
    mutationFn: (invoiceReference: string) => inventoryApi.deleteLatestDelivery(pumpId, invoiceReference),
    onSuccess: () => {
      setDeleteInvoice(null)
      queryClient.invalidateQueries({ queryKey: ['deliveries', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pumpId] })
    },
    onError: () => queryClient.invalidateQueries({ queryKey: ['deliveries', pumpId] }),
  })

  const { data: deliveriesPage, isLoading: deliveriesLoading } = useQuery({
    queryKey: ['deliveries', pumpId, deliveryPage, deliveryPageSize],
    queryFn:  () => inventoryApi.getDeliveries(pumpId, deliveryPage, deliveryPageSize),
    enabled:  deliveryHistOpen,
  })
  const deliveries = deliveriesPage?.content ?? []

  // Only the most recently entered invoice can be deleted; it may be back-dated, so it is not always the top group.
  const { data: latestInvoiceLines = [] } = useQuery({
    queryKey: ['deliveries', pumpId, 'latest'],
    queryFn:  () => inventoryApi.getLatestInvoiceLines(pumpId),
    enabled:  deliveryHistOpen && canDeleteInvoice,
  })
  const latestInvoice = latestInvoiceLines[0]?.invoiceReference ?? null

  // When delivery data loads, collapse all groups except the first (newest) one
  useEffect(() => {
    const content = deliveriesPage?.content
    if (!content?.length) return
    const keys = [...new Set(content.map(d => d.invoiceReference || NO_INVOICE_KEY))]
      .sort((a, b) => {
        const dateA = content.find(d => (d.invoiceReference || NO_INVOICE_KEY) === a)?.deliveryDate ?? ''
        const dateB = content.find(d => (d.invoiceReference || NO_INVOICE_KEY) === b)?.deliveryDate ?? ''
        return dateB.localeCompare(dateA)
      })
    setCollapsedDeliveries(new Set(keys.slice(1)))
  }, [deliveriesPage])

  return (
    <>
      <div className="ui-card p-0 overflow-hidden">
        <button
          onClick={() => setDeliveryHistOpen(v => !v)}
          className="ui-accordion-trigger"
        >
          <span className="ui-accordion-title">{t('deliveries.historyTitle')}</span>
          <span className={`ui-accordion-arrow ${deliveryHistOpen ? 'ui-accordion-arrow--open' : ''}`}><ChevronDown size={14} strokeWidth={2} /></span>
        </button>
        {deliveryHistOpen && (
          <div className="ui-accordion-content border-t border-border">
            <div className="ui-toolbar">
              <p className="ui-toolbar-title">{t('deliveries.filterTitle')}</p>
              <div className="ui-toolbar-actions">
                <div className="w-44">
                <SearchableSelect label={t('deliveries.filterByTank')}
                  value={deliveryTankFilter}
                  onChange={v => setDeliveryTankFilter(v)}
                  options={[
                    { value: '', label: t('filters.allTanks') },
                    ...tanks.map(tank => ({ value: tank.tankIdentifier, label: tank.tankIdentifier })),
                  ]}
                  placeholder={t('filters.byTank')}
                />
              </div>
                <div className="w-44">
                <SearchableSelect label={t('deliveries.filterByFuel')}
                  value={deliveryFuelFilter}
                  onChange={v => setDeliveryFuelFilter(v)}
                  options={[
                    { value: '', label: t('filters.allFuelTypes') },
                    ...Array.from(new Set(tanks.map(tank => tank.fuelType))).map(ft => ({ value: ft, label: codeLabel(ft) })),
                  ]}
                  placeholder={t('filters.byFuelType')}
                />
              </div>
                {(deliveryTankFilter || deliveryFuelFilter) && (
                  <button
                    onClick={() => { setDeliveryTankFilter(''); setDeliveryFuelFilter('') }}
                    className="ui-btn ui-btn-ghost text-xs"
                  >
                    {t('common:actions.clearFilters')}
                  </button>
                )}
              </div>
            </div>

            {deliveriesLoading ? (
              <div className="p-5 text-sm text-ink-3">{t('common:loading.app')}</div>
            ) : (() => {
              // Apply client-side filters on the current page's data
              const filtered = deliveries.filter(d =>
                (!deliveryTankFilter || d.tankIdentifier === deliveryTankFilter) &&
                (!deliveryFuelFilter || d.fuelType === deliveryFuelFilter)
              )

              if (filtered.length === 0) {
                return (
                  <div className="p-5 text-sm text-ink-3">
                    {deliveries.length === 0
                      ? t('deliveries.empty')
                      : t('deliveries.noMatch')}
                  </div>
                )
              }

              // Group by invoice number, sorted newest-first (within current page)
              const groups = Object.entries(
                filtered.reduce<Record<string, TankerDelivery[]>>((acc, d) => {
                  const key = d.invoiceReference || NO_INVOICE_KEY
                  ;(acc[key] ??= []).push(d)
                  return acc
                }, {})
              ).sort(([, a], [, b]) => b[0].deliveryDate.localeCompare(a[0].deliveryDate))

              return (
                <>
                  <div className="divide-y divide-border">
                    {groups.map(([invoice, rows]) => {
                      const totalQty = rows.reduce((s, r) => s + r.quantityDelivered, 0)
                      const totalCost = rows.reduce((s, r) => s + r.totalCost, 0)
                      const isCollapsed = collapsedDeliveries.has(invoice)
                      const isLatestEnteredInvoice = latestInvoice !== null && invoice === latestInvoice
                      const toggleDelivery = () => setCollapsedDeliveries(prev => withToggled(prev, invoice))
                      return (
                        <div key={invoice} className="px-5 py-4 space-y-2">
                          <div className="flex items-center gap-1">
                            <button
                              type="button"
                              onClick={toggleDelivery}
                              className="flex-1 flex items-center gap-2 text-left hover:opacity-70 transition-opacity min-w-0"
                            >
                              <span className="text-xs text-ink-3">{t('deliveries.billNo')}</span>
                              <span className="font-mono text-xs font-semibold text-info bg-info-bg border border-border px-2.5 py-1 rounded-control">
                                {invoice === NO_INVOICE_KEY ? t('deliveries.noInvoice') : invoice}
                              </span>
                              <span className="text-xs text-ink-3">{fmtDate(rows[0].deliveryDate)}</span>
                              <span className="text-xs text-ink-3">·</span>
                              <span className="text-xs text-ink-3">{t('deliveries.tankCount', { count: rows.length })}</span>
                              {rows[0].tankerName && (
                                <>
                                  <span className="text-xs text-ink-3">·</span>
                                  <span className="inline-flex items-center gap-1 text-xs text-ink-2 bg-surface-sunken border border-border px-2 py-0.5 rounded-badge">
                                    <Truck size={10} strokeWidth={2} />
                                    {rows[0].tankerName}
                                  </span>
                                </>
                              )}
                              <div className="ml-auto flex items-center gap-3">
                                <LitresText value={totalQty} className="text-xs text-ink-3" />
                                {isOwnerOrAdmin && (rows[0].billTotal != null ? (
                                  <div className="flex flex-col items-end leading-tight">
                                    <MoneyText value={Number(rows[0].billTotal)} className="text-sm font-bold text-ink" />
                                    {Math.abs(Number(rows[0].billTotal) - totalCost) > 0.005 && (
                                      <span className="text-xs text-ink-3">
                                        {t('deliveries.calculated', { amount: formatNumberIn(totalCost, { minimumFractionDigits: 2 }) })}
                                      </span>
                                    )}
                                  </div>
                                ) : (
                                  <MoneyText value={totalCost} className="text-sm font-bold text-ink" />
                                ))}
                                <span className={`ui-accordion-arrow ml-1 ${!isCollapsed ? 'ui-accordion-arrow--open' : ''}`}><ChevronDown size={14} strokeWidth={2} /></span>
                              </div>
                            </button>
                            {isLatestEnteredInvoice && canDeleteInvoice && (
                              <IconButton
                                onClick={() => setDeleteInvoice({ invoice, rows: latestInvoiceLines })}
                                className="shrink-0 ml-2 text-ink-3 hover:text-bad transition-colors rounded-control"
                                label={t('deliveries.deleteInvoice')}
                                icon={<Trash2 size={14} />}
                              />
                            )}
                          </div>

                          {!isCollapsed && (
                            <div className="ui-accordion-content ui-card p-0 overflow-hidden">
                              <div className="px-4 py-3">
                                <div className={`grid gap-3 border-b border-border pb-2 text-xs font-semibold uppercase tracking-wide text-ink-3 ${
                                  isOwnerOrAdmin
                                    ? 'grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)_auto]'
                                    : 'grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,1fr)_auto]'
                                }`}>
                                  <span>{t('columns.tank')}</span>
                                  <span>{t('columns.fuel')}</span>
                                  <span className="text-right">{t('columns.quantity')}</span>
                                  {isOwnerOrAdmin && <span className="text-right">{t('columns.rate')}</span>}
                                  {isOwnerOrAdmin && <span className="text-right">{t('columns.amount')}</span>}
                                  <span />
                                </div>
                                <div className="space-y-2 pt-2">
                                  {rows.map(d => <DeliveryItem key={d.id} delivery={d} pumpId={pumpId!} isOwnerOrAdmin={isOwnerOrAdmin} />)}
                                </div>
                              </div>
                            </div>
                          )}
                        </div>
                      )
                    })}
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
              )
            })()}
          </div>
        )}
      </div>
      {deleteInvoice && (
        <DeleteInvoiceModal
          invoice={deleteInvoice.invoice}
          rows={deleteInvoice.rows}
          isPending={deleteLatestMutation.isPending}
          error={deleteLatestMutation.error ? parseApiError(deleteLatestMutation.error, t('deliveries.deleteFailed')) : null}
          onConfirm={() => deleteLatestMutation.mutate(deleteInvoice.invoice)}
          onClose={() => { setDeleteInvoice(null); deleteLatestMutation.reset() }}
        />
      )}
    </>
  )
}
