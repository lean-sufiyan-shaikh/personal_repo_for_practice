import { useState, useEffect } from 'react'
import { withToggled } from '../../utils/setToggle'
import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Check, ChevronDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../api/inventoryApi'
import type { TankStock, DipCheck } from '../../api/inventoryApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'
import { StatusBadge } from '../../components/StatusBadge'
import { DipItem } from './DipItem'
import { fmtDate } from './inventoryFormat'

export function DipHistory({ pumpId, tanks, canViewFinancials }: { pumpId: number; tanks: TankStock[]; canViewFinancials: boolean }) {
  const { t } = useTranslation('inventory')
  const [dipHistOpen,       setDipHistOpen]       = useState(false)
  const [dipPage,           setDipPage]           = useState(0)
  const [dipPageSize,       setDipPageSize]       = useState(10)
  const [dipTankFilter,          setDipTankFilter]          = useState('')
  const [dipFuelFilter,          setDipFuelFilter]          = useState('')
  const [collapsedDipGroups,     setCollapsedDipGroups]     = useState<Set<string>>(new Set())

  const { data: dipChecksPage, isLoading: dipLoading } = useQuery({
    queryKey: ['dipChecks', pumpId, dipPage, dipPageSize],
    queryFn:  () => inventoryApi.getDipChecks(pumpId, dipPage, dipPageSize),
    enabled:  dipHistOpen,
  })
  const dipChecks = dipChecksPage?.content ?? []

  // When DIP data loads, collapse all groups except the first (newest) one
  useEffect(() => {
    const content = dipChecksPage?.content
    if (!content?.length) return
    const keys = [...new Set(content.map(d => d.checkedAt.slice(0, 10)))]
      .sort((a, b) => b.localeCompare(a))
    setCollapsedDipGroups(new Set(keys.slice(1)))
  }, [dipChecksPage])

  return (
      <div className="ui-card p-0 overflow-hidden">
        <button
          onClick={() => setDipHistOpen(v => !v)}
          className="ui-accordion-trigger"
        >
          <span className="ui-accordion-title">{t('dipHistory.title')}</span>
          <span className={`ui-accordion-arrow ${dipHistOpen ? 'ui-accordion-arrow--open' : ''}`}><ChevronDown size={14} strokeWidth={2} /></span>
        </button>
        {dipHistOpen && (
          <div className="ui-accordion-content border-t border-border">
            <div className="ui-toolbar">
              <p className="ui-toolbar-title">{t('dipHistory.filterTitle')}</p>
              <div className="ui-toolbar-actions">
                <div className="w-44">
                <SearchableSelect label={t('dipHistory.filterByTank')}
                  value={dipTankFilter}
                  onChange={v => setDipTankFilter(v)}
                  options={[
                    { value: '', label: t('filters.allTanks') },
                    ...tanks.map(tank => ({ value: tank.tankIdentifier, label: tank.tankIdentifier })),
                  ]}
                  placeholder={t('filters.byTank')}
                />
              </div>
                <div className="w-44">
                <SearchableSelect label={t('dipHistory.filterByFuel')}
                  value={dipFuelFilter}
                  onChange={v => setDipFuelFilter(v)}
                  options={[
                    { value: '', label: t('filters.allFuelTypes') },
                    ...Array.from(new Set(tanks.map(tank => tank.fuelType))).map(ft => ({ value: ft, label: ft })),
                  ]}
                  placeholder={t('filters.byFuelType')}
                />
              </div>
                {(dipTankFilter || dipFuelFilter) && (
                  <button
                    onClick={() => { setDipTankFilter(''); setDipFuelFilter('') }}
                    className="ui-btn ui-btn-ghost text-xs"
                  >
                    {t('filters.clear')}
                  </button>
                )}
              </div>
            </div>

            {dipLoading ? (
              <div className="p-5 text-sm text-ink-3">{t('filters.loading')}</div>
            ) : (() => {
              // Apply client-side filters on the current page's data
              const filtered = dipChecks.filter(d =>
                (!dipTankFilter || d.tankIdentifier === dipTankFilter) &&
                (!dipFuelFilter || d.fuelType === dipFuelFilter)
              )

              if (filtered.length === 0) {
                return (
                  <div className="p-5 text-sm text-ink-3">
                    {dipChecks.length === 0
                      ? t('dipHistory.empty')
                      : t('dipHistory.noMatch')}
                  </div>
                )
              }

              // Group by calendar date, sorted newest-first (within current page)
              const groups = Object.entries(
                filtered.reduce<Record<string, DipCheck[]>>((acc, d) => {
                  const key = d.checkedAt.slice(0, 10)
                  ;(acc[key] ??= []).push(d)
                  return acc
                }, {})
              ).sort(([a], [b]) => b.localeCompare(a))

              return (
                <>
                <div className="divide-y divide-border">
                  {groups.map(([dateKey, rows]) => {
                    const allBalanced = rows.every(r => r.variance === 0)
                    const isCollapsed = collapsedDipGroups.has(dateKey)
                    const toggleDip = () => setCollapsedDipGroups(prev => withToggled(prev, dateKey))
                    return (
                      <div key={dateKey} className="px-5 py-4 space-y-2">
                        <button
                          type="button"
                          onClick={toggleDip}
                          className="w-full flex items-center gap-2 text-left hover:opacity-70 transition-opacity"
                        >
                          <span className="text-xs font-semibold text-ink bg-surface-sunken px-2.5 py-1 rounded-control">
                            {fmtDate(dateKey)}
                          </span>
                          <span className="text-xs text-ink-3">
                            {t('dipHistory.tanksChecked', { count: rows.length })}
                          </span>
                          {allBalanced && (
                            <StatusBadge tone="ok" icon={Check} label={t('dipHistory.allBalanced')} className="ml-auto" />
                          )}
                          {!allBalanced && (
                            <StatusBadge tone="warn" icon={AlertTriangle} label={t('dipHistory.varianceDetected')} className="ml-auto" />
                          )}
                          <span className={`ui-accordion-arrow ml-1 ${!isCollapsed ? 'ui-accordion-arrow--open' : ''}`}><ChevronDown size={14} strokeWidth={2} /></span>
                        </button>
                        {!isCollapsed && (
                          <div className="ui-accordion-content space-y-1.5">
                            {rows.map(d => (
                              <DipItem
                                key={d.id}
                                check={d}
                                pumpId={pumpId}
                                canReview={canViewFinancials}
                              />
                            ))}
                          </div>
                        )}
                      </div>
                    )
                  })}
                </div>
                {dipChecksPage && (
                  <div className="px-5">
                    <Pagination
                      data={dipChecksPage}
                      onPageChange={p => setDipPage(p)}
                      onPageSizeChange={s => { setDipPageSize(s); setDipPage(0) }}
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
  )
}
