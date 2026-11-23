import { useState, useRef, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../utils/apiError'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary, TankInfo, UpdateTankRequest } from '../../api/pumpApi'
import type { FuelType } from '../../types/shift'
import { FUEL_UNIT } from '../../constants/fuel'
import { codeLabel } from '../../constants/labels'
import { formatNumberIn } from '../../i18n/format'
import { ALL_FUEL_TYPES, FUEL_COLOR } from './fuelStyles'
import { ConfirmButton } from '../../components/ConfirmButton'
import { StatusBadge } from '../../components/StatusBadge'

// ── Tanks content ─────────────────────────────────────────────────────────────
// Owner manually creates each underground tank (name, fuel type, capacity).
// Multiple tanks per fuel type are supported (e.g. 3 petrol tanks).

export function TanksContent({ pump }: { pump: PumpSummary }) {
  const { t } = useTranslation(['setup', 'common'])
  const queryClient = useQueryClient()

  // Create form state
  const [createIdentifier, setCreateIdentifier] = useState('')
  const [createFuelType, setCreateFuelType]     = useState<FuelType>('PETROL')
  const [createCapacity, setCreateCapacity]     = useState('')
  const [createDipTol, setCreateDipTol]         = useState('')
  const [createError, setCreateError]           = useState<string | null>(null)

  // Fuel type dropdown open/close
  const [fuelDropdownOpen, setFuelDropdownOpen] = useState(false)
  const fuelDropdownRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (fuelDropdownRef.current && !fuelDropdownRef.current.contains(e.target as Node)) {
        setFuelDropdownOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // Edit form state
  const [editingId, setEditingId]         = useState<number | null>(null)
  const [editCapacity, setEditCapacity]   = useState('')
  const [editIdentifier, setEditIdentifier] = useState('')
  const [editError, setEditError]         = useState<string | null>(null)

  // Disable confirmation: null = no confirm shown; tankId = confirm dialog for that tank
  const [statusError, setStatusError]           = useState<string | null>(null)

  const { data: tanks = [], isLoading } = useQuery({
    queryKey: ['tanks', pump.id],
    queryFn:  () => pumpApi.getTanks(pump.id),
  })

  const createMutation = useMutation({
    mutationFn: (req: { tankIdentifier: string; fuelType: FuelType; capacity: number; dipTolerance?: number }) =>
      pumpApi.createTank(pump.id, req),
    onSuccess: () => {
      setCreateIdentifier(''); setCreateCapacity(''); setCreateDipTol(''); setCreateError(null)
      queryClient.invalidateQueries({ queryKey: ['tanks', pump.id] })
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pump.id] })
    },
    onError: (err) => setCreateError(parseApiError(err, t('tanks.createFailed'))),
  })

  const updateMutation = useMutation({
    mutationFn: ({ tankId, req }: { tankId: number; req: UpdateTankRequest }) => pumpApi.updateTank(pump.id, tankId, req),
    onSuccess: () => {
      setEditingId(null); setEditError(null)
      queryClient.invalidateQueries({ queryKey: ['tanks', pump.id] })
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pump.id] })
    },
    onError: (err) => setEditError(parseApiError(err, t('tanks.updateFailed'))),
  })

  const statusMutation = useMutation({
    mutationFn: ({ tankId, status }: { tankId: number; status: 'ACTIVE' | 'INACTIVE' }) =>
      pumpApi.updateTankStatus(pump.id, tankId, status),
    onSuccess: () => {
      setStatusError(null)
      queryClient.invalidateQueries({ queryKey: ['tanks', pump.id] })
      queryClient.invalidateQueries({ queryKey: ['tankStocks', pump.id] })
    },
    onError: (err) => setStatusError(parseApiError(err, t('tanks.statusFailed'))),
  })

  /** Returns true when currentStock > 5% of capacity (significant frozen stock warning). */
  const hasSignificantStock = (tank: TankInfo) =>
    tank.capacity > 0 && (tank.currentStock / tank.capacity) > 0.05

  const submitCreate = (e: React.FormEvent) => {
    e.preventDefault(); setCreateError(null)
    if (!createIdentifier.trim()) { setCreateError(t('tanks.nameRequired')); return }
    if (!createCapacity || Number(createCapacity) <= 0) { setCreateError(t('tanks.capacityPositive')); return }
    createMutation.mutate({
      tankIdentifier: createIdentifier.trim(),
      fuelType: createFuelType,
      capacity: Number(createCapacity),
      dipTolerance: createDipTol ? Number(createDipTol) : undefined,
    })
  }

  const submitEdit = (e: React.FormEvent, tank: TankInfo) => {
    e.preventDefault(); setEditError(null)
    if (!editCapacity || Number(editCapacity) <= 0) { setEditError(t('tanks.capacityPositive')); return }
    updateMutation.mutate({
      tankId: tank.id,
      req: { capacity: Number(editCapacity), tankIdentifier: editIdentifier.trim() || undefined },
    })
  }

  return (
    <div className="space-y-4">
      <p className="text-xs text-ink-3">
        {t('tanks.intro')}
      </p>

      {/* Existing tanks */}
      {isLoading ? (
        <p className="text-xs text-ink-3">{t('tanks.loading')}</p>
      ) : tanks.length === 0 ? (
        <p className="text-xs text-ink-3">{t('tanks.empty')}</p>
      ) : (
        <div className="space-y-2">
          {statusError && (
            <div role="alert" className="ui-alert ui-alert-danger text-sm">
              <p className="text-bad text-xs">{statusError}</p>
            </div>
          )}
          {(tanks as TankInfo[]).map((tank) => {
            const isInactive = tank.status === 'INACTIVE'
            return (
              <div key={tank.id} className={`border rounded-control overflow-hidden transition-opacity ${
                isInactive ? 'border-border bg-bg opacity-70' : 'border-border bg-surface'
              }`}>
                <div className="flex items-center justify-between px-3 py-2.5">
                  <div className="flex items-center gap-2.5 flex-wrap">
                    <span className={`text-xs font-bold px-2 py-0.5 rounded-badge border ${
                      isInactive ? 'bg-surface-sunken text-ink-3 border-border' : (FUEL_COLOR[tank.fuelType] ?? 'bg-surface-sunken text-ink-2 border-border')
                    }`}>
                      {codeLabel(tank.fuelType)}
                    </span>
                    <span className={`text-sm font-semibold ${isInactive ? 'text-ink-3' : 'text-ink'}`}>
                      {tank.tankIdentifier}
                    </span>
                    {isInactive && (
                      <StatusBadge tone="warn" label={t('tanks.disabled')} />
                    )}
                    <span className="text-xs text-ink-3">
                      {t('tanks.capacityPrefix')} <span className="text-ink-3 font-medium">
                        {formatNumberIn(Number(tank.capacity), { minimumFractionDigits: 0 })} {FUEL_UNIT[tank.fuelType] ?? 'L'}
                      </span>
                      {isInactive && (
                        <span className="ml-1 text-warn">{t('tanks.stockFrozen')}</span>
                      )}
                    </span>
                  </div>

                  <div className="flex items-center gap-2 ml-2 shrink-0">
                    {/* Edit is only available for active tanks */}
                    {!isInactive && (
                        <button type="button"
                          onClick={() => {
                            if (editingId === tank.id) { setEditingId(null) } else {
                              setEditingId(tank.id); setEditCapacity(String(tank.capacity))
                              setEditIdentifier(tank.tankIdentifier); setEditError(null)
                            }
                          }}
                          className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-primary hover:text-primary-strong">
                          {editingId === tank.id ? t('common:actions.cancel') : t('actions.edit')}
                        </button>
                    )}

                    {/* Disable / Enable toggle */}
                    {isInactive ? (
                      <button
                        type="button"
                        onClick={() => { setStatusError(null); statusMutation.mutate({ tankId: tank.id, status: 'ACTIVE' }) }}
                        disabled={statusMutation.isPending}
                        className="min-h-6 text-xs font-medium text-ok transition-colors hover:underline disabled:opacity-50">
                        {t('actions.enable')}
                      </button>
                    ) : (
                      <ConfirmButton
                        triggerLabel={t('actions.disable')}
                        triggerAriaLabel={t('tanks.disableAria', { name: tank.tankIdentifier })}
                        prompt={hasSignificantStock(tank)
                          ? t('tanks.freezePrompt', { percent: ((tank.currentStock / tank.capacity) * 100).toFixed(0) })
                          : t('tanks.disablePrompt')}
                        onConfirm={() => { setStatusError(null); statusMutation.mutate({ tankId: tank.id, status: 'INACTIVE' }) }}
                        disabled={statusMutation.isPending}
                        triggerClassName="min-h-6 text-xs font-medium text-warn transition-colors hover:text-warn-strong disabled:opacity-50"
                        confirmClassName="ui-btn ui-btn-warning min-h-6 px-2 py-1 text-xs"
                      />
                    )}
                  </div>
                </div>

                {editingId === tank.id && !isInactive && (
                  <form onSubmit={(e) => submitEdit(e, tank)}
                    className="px-3 py-3 border-t border-border bg-bg space-y-2">
                    <div className="flex gap-3 items-end flex-wrap">
                      <div>
                        <label className="block">
                          <span className="ui-label">
                          {t('tanks.capacityLabel', { unit: FUEL_UNIT[tank.fuelType] ?? 'L' })}
                        </span>
                          <input type="number" step="0.01" min="1" value={editCapacity}
                            onChange={(e) => setEditCapacity(e.target.value)}
                            className="w-36 text-xs min-h-10"
                            placeholder={t('example', { value: '25000' })} />
                        </label>
                      </div>
                      <div>
                        <label className="block">
                          <span className="ui-label">{t('tanks.nameLabel')}</span>
                          <input type="text" value={editIdentifier}
                            onChange={(e) => setEditIdentifier(e.target.value)}
                            className="w-36 text-xs min-h-10"
                            placeholder={t('tanks.namePlaceholder')} />
                        </label>
                      </div>
                      <button type="submit" disabled={updateMutation.isPending}
                        className="ui-btn ui-btn-primary min-h-0 px-3 py-1.5 text-xs">
                        {updateMutation.isPending ? t('actions.savingDots') : t('actions.save')}
                      </button>
                    </div>
                    {editError && <p role="alert" className="ui-error-text">{editError}</p>}
                  </form>
                )}
              </div>
            )
          })}
        </div>
      )}

      {/* Add tank form */}
      <div className={tanks.length > 0 ? 'border-t border-border pt-4' : ''}>
        <p className="ui-label mb-3">
          {tanks.length === 0 ? t('tanks.addFirst') : t('tanks.addAnother')}
        </p>
        <form onSubmit={submitCreate} className="space-y-3">
          <div className="flex gap-3 items-end flex-wrap">
            <div>
              <label className="block">
                <span className="ui-label">{t('tanks.nameLabel')} <span className="text-bad">*</span></span>
                <input required type="text" value={createIdentifier}
                  onChange={(e) => setCreateIdentifier(e.target.value)}
                  placeholder={t('tanks.namePlaceholder')}
                  className="w-36 text-sm" />
              </label>
            </div>
            <div className="relative" ref={fuelDropdownRef}>
              <span className="ui-label">{t('tanks.fuelType')} <span className="text-bad">*</span></span>
              <button
                type="button"
                onClick={() => setFuelDropdownOpen(o => !o)}
                className="ui-btn ui-btn-secondary min-w-37 justify-between bg-surface text-sm"
              >
                <span className={`inline-flex items-center px-2 py-0.5 rounded-badge text-xs font-medium border ${FUEL_COLOR[createFuelType]}`}>
                  {codeLabel(createFuelType)}
                </span>
                <svg className={`w-4 h-4 text-ink-3 transition-transform duration-150 ${fuelDropdownOpen ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                </svg>
              </button>
              {fuelDropdownOpen && (
                <div className="absolute z-20 mt-1 ui-card p-1 min-w-40 shadow-overlay">
                  {ALL_FUEL_TYPES.map((ft) => (
                    <button
                      key={ft}
                      type="button"
                      onClick={() => { setCreateFuelType(ft); setFuelDropdownOpen(false) }}
                      className={`w-full flex items-center gap-2 px-3 py-2 text-sm hover:bg-bg transition-colors ${createFuelType === ft ? 'bg-bg' : ''}`}
                    >
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-badge text-xs font-medium border ${FUEL_COLOR[ft]}`}>
                        {codeLabel(ft)}
                      </span>
                      {createFuelType === ft && (
                        <svg className="ml-auto w-4 h-4 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                      )}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div>
              <label className="block">
                <span className="ui-label">
                {t('tanks.capacityLabel', { unit: FUEL_UNIT[createFuelType] })} <span className="text-bad">*</span>
              </span>
                <input required type="number" step="0.01" min="1" value={createCapacity}
                  onChange={(e) => setCreateCapacity(e.target.value)}
                  placeholder={t('example', { value: '25000' })}
                  className="w-32 text-sm" />
              </label>
            </div>
            <div>
              <label className="block">
                <span className="ui-label">
                {t('tanks.dipTolerance', { unit: FUEL_UNIT[createFuelType] })} <span className="text-ink-3">{t('optionalHint')}</span>
              </span>
                <input type="number" step="0.01" min="0" value={createDipTol}
                  onChange={(e) => setCreateDipTol(e.target.value)}
                  placeholder={t('example', { value: '50' })}
                  className="w-28 text-sm" />
              </label>
            </div>
            <button type="submit" disabled={createMutation.isPending}
              className="ui-btn ui-btn-primary">
              {createMutation.isPending ? t('actions.addingDots') : t('tanks.add')}
            </button>
          </div>
          {createError && <p role="alert" className="ui-error-text">{createError}</p>}
        </form>
      </div>
    </div>
  )
}

// ── Shift Definitions Content ─────────────────────────────────────────────────

/**
 * Subtracts 1 minute from a "HH:MM" time string.
 * End times are stored as exclusive upper bounds: user sees "09:00", backend stores "08:59".
 * This ensures consecutive shifts (one ending at 09:00, next starting at 09:00) don't overlap.
 */
