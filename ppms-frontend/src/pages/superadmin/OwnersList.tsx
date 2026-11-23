import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Fuel, X, Plus, KeyRound } from 'lucide-react'
import { superAdminApi } from '../../api/superAdminApi'
import type { OwnerSummary, PumpSummary, UpdatePumpRequest } from '../../api/superAdminApi'
import { Pagination } from '../../components/Pagination'
import { SkeletonRows } from '../../components/Skeleton'
import { formatIstDate } from '../../utils/date'
import { parseApiError } from '../../utils/apiError'
import { AddPumpForm } from './AddPumpForm'
import { PumpCard } from './PumpCard'
import { ResetPasswordDialog } from '../../components/ResetPasswordDialog'
import { StatusBadge } from '../../components/StatusBadge'
import type { TFunction } from 'i18next'

const ownersPageSize = 10

interface OwnersListProps {
  owners: OwnerSummary[]
  ownersLoading: boolean
}

function ownerStatusLabel(t: TFunction<'superadmin'>, status: string): string {
  if (status === 'ACTIVE') return t('owners.statusActive')
  if (status === 'INACTIVE') return t('owners.statusInactive')
  return status.toLowerCase()
}

export function OwnersList({ owners, ownersLoading }: OwnersListProps) {
  const { t } = useTranslation('superadmin')
  const queryClient = useQueryClient()

  // Which owner's "Add Pump" form is currently open (null = none)
  const [addPumpOwnerId, setAddPumpOwnerId] = useState<number | null>(null)

  // Which pump's "Edit" form is currently open (null = none)
  const [editingPumpId, setEditingPumpId] = useState<number | null>(null)
  const [editPumpError, setEditPumpError] = useState<string | null>(null)

  // Owners list pagination
  const [ownersPage, setOwnersPage] = useState(0)

  const [ownerToReset, setOwnerToReset] = useState<OwnerSummary | null>(null)

  const editPumpMutation = useMutation({
    mutationFn: ({ pumpId, data }: { pumpId: number; data: UpdatePumpRequest }) =>
      superAdminApi.updatePump(pumpId, data),
    onSuccess: () => {
      setEditingPumpId(null)
      setEditPumpError(null)
      queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })
    },
    onError: (err) =>
      setEditPumpError(parseApiError(err, t('owners.updatePumpFailed'))),
  })

  function toggleAddPump(ownerId: number) {
    setAddPumpOwnerId(addPumpOwnerId === ownerId ? null : ownerId)
  }

  function toggleEditPump(pump: PumpSummary) {
    setEditingPumpId(editingPumpId === pump.pumpId ? null : pump.pumpId)
    setEditPumpError(null)
  }

  function togglePumpEnabled(pump: PumpSummary) {
    editPumpMutation.mutate({
      pumpId: pump.pumpId,
      data: { pumpName: pump.pumpName, pumpAddress: pump.pumpAddress, enabled: !pump.enabled },
    })
  }

  return (
    <div className="lg:col-span-3">
      <div className="ui-card p-0">
        <div className="px-5 py-4 border-b border-border flex items-center justify-between">
          <div>
            <h2 className="text-sm font-semibold text-ink">{t('owners.title')}</h2>
            <p className="text-xs text-ink-3 mt-0.5">{t('owners.subtitle')}</p>
          </div>
          <StatusBadge tone="info" label={t('owners.count', { count: owners.length })} />
        </div>

        {ownersLoading ? (
          <div className="px-5 py-4"><SkeletonRows count={5} /></div>
        ) : owners.length === 0 ? (
          <div className="ui-empty px-5 py-8">
            <p className="ui-subtitle">{t('owners.empty')}</p>
            <p className="text-xs text-ink-3 mt-1">{t('owners.emptyHint')}</p>
          </div>
        ) : (() => {
          const totalPages = Math.max(1, Math.ceil(owners.length / ownersPageSize))
          const safePage   = Math.min(ownersPage, totalPages - 1)
          const pageOwners = owners.slice(safePage * ownersPageSize, (safePage + 1) * ownersPageSize)
          const pagedData  = {
            content: pageOwners, page: safePage, pageSize: ownersPageSize,
            totalElements: owners.length, totalPages,
            hasNext: safePage < totalPages - 1, hasPrevious: safePage > 0,
          }
          return (
            <>
              <div className="divide-y divide-border">
                {pageOwners.map((owner) => {
                  const isPumpFormOpen = addPumpOwnerId === owner.ownerId
                  const enabledPumps   = owner.pumps.filter(p => p.enabled).length
                  return (
                    <div key={owner.ownerId}>
                      <div className="px-5 py-4">
                        {/* Owner header row */}
                        <div className="flex items-start justify-between gap-3">
                          <div className="flex items-center gap-3 min-w-0">
                            <div className="w-9 h-9 bg-info-bg border border-info rounded-full flex items-center justify-center flex-shrink-0">
                              <span className="text-sm font-bold text-primary">
                                {owner.fullName.charAt(0).toUpperCase()}
                              </span>
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-2 flex-wrap">
                                <p className="text-sm font-semibold text-ink">{owner.fullName}</p>
                                <StatusBadge tone={owner.status === 'ACTIVE' ? 'ok' : 'neutral'} label={ownerStatusLabel(t, owner.status)} />
                              </div>
                              <p className="text-xs text-ink-3 font-mono mt-0.5">{owner.phoneNumber}</p>
                              {/* Pump count summary */}
                              <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                                <span className="inline-flex items-center gap-1 text-xs text-ink-2 bg-neutral-bg px-2 py-0.5 rounded-badge">
                                  <Fuel size={12} strokeWidth={2} aria-hidden="true" />{t('owners.pumpCount', { count: owner.pumps.length })}
                                  {owner.pumps.length > enabledPumps && (
                                    <span className="text-bad">{t('owners.disabledCount', { value: owner.pumps.length - enabledPumps })}</span>
                                  )}
                                </span>
                              </div>
                            </div>
                          </div>
                          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              type="button"
              onClick={() => setOwnerToReset(owner)}
              className="ui-btn min-h-0 px-3 py-1 text-xs border border-border text-ink-2 transition-colors hover:border-warn hover:text-warn hover:bg-warn-bg"
            >
              <span className="inline-flex items-center gap-1"><KeyRound size={12} strokeWidth={2} />{t('owners.resetPassword')}</span>
            </button>
            <button
              onClick={() => toggleAddPump(owner.ownerId)}
              className={`ui-btn min-h-0 px-3 py-1 text-xs border transition-colors ${
                isPumpFormOpen
                  ? 'border-primary text-info bg-info-bg'
                  : 'border-border text-ink-2 hover:border-info hover:text-info hover:bg-info-bg'
              }`}
            >
                              {isPumpFormOpen ? <span className="inline-flex items-center gap-1"><X size={12} strokeWidth={2} />{t('owners.cancel')}</span> : <span className="inline-flex items-center gap-1"><Plus size={12} strokeWidth={2.5} />{t('owners.addPump')}</span>}
                            </button>
                          </div>
                        </div>

                        {/* Pumps list */}
                        {owner.pumps.length > 0 && (
                          <div className="mt-3 ml-12 space-y-2">
                            {owner.pumps.map((pump) => {
                              const isEditOpen = editingPumpId === pump.pumpId
                              return (
                                <PumpCard
                                  key={pump.pumpId}
                                  pump={pump}
                                  isEditOpen={isEditOpen}
                                  onToggleEdit={() => toggleEditPump(pump)}
                                  onToggleEnabled={() => togglePumpEnabled(pump)}
                                  isSaving={editPumpMutation.isPending}
                                  editError={editPumpError}
                                  onSaveEdit={(data) => {
                                    setEditPumpError(null)
                                    editPumpMutation.mutate({ pumpId: pump.pumpId, data })
                                  }}
                                />
                              )
                            })}
                          </div>
                        )}

                        {owner.pumps.length === 0 && (
                          <p className="ml-12 mt-2 ui-help italic">{t('owners.noPumps')}</p>
                        )}

                        <div className="mt-2 ml-12 flex items-center gap-3 ui-help">
                          <span>{t('owners.idLabel')} <span className="font-mono text-ink-3">{owner.employeeId}</span></span>
                          <span>·</span>
                          <span>{t('owners.joined', { date: formatIstDate(owner.createdAt) })}</span>
                        </div>
                      </div>

                      {/* Inline Add Pump form */}
                      {isPumpFormOpen && (
                        <AddPumpForm owner={owner} onDone={() => setAddPumpOwnerId(null)} />
                      )}
                    </div>
                  )
                })}
              </div>
              {owners.length > ownersPageSize && (
                <div className="px-5 border-t border-border">
                  <Pagination
                    data={pagedData}
                    onPageChange={setOwnersPage}
                    onPageSizeChange={() => {}}
                    pageSizeOptions={[10]}
                  />
                </div>
              )}
            </>
          )
        })()}
      </div>

      {ownerToReset && (
        <ResetPasswordDialog
          userId={ownerToReset.ownerId}
          userName={ownerToReset.fullName}
          onClose={() => setOwnerToReset(null)}
        />
      )}
    </div>
  )
}
