import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { vehicleApi } from '../../../api/vehicleApi'
import type { ClientVehicle, SaveVehicleInput, VehicleCheckMode } from '../../../api/vehicleApi'
import { EmptyState } from '../../../components/EmptyState'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { VehicleCard } from './VehicleCard'
import { VehicleCheckSetting } from './VehicleCheckSetting'
import { VehicleForm } from './VehicleForm'

interface ClientVehiclesTabProps {
  pumpId: number
  clientId: number
}

/** The account's approved vehicles and its vehicle check, managed by owners, admins and managers. */
export function ClientVehiclesTab({ pumpId, clientId }: ClientVehiclesTabProps) {
  const { t } = useTranslation('credit')
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [editing, setEditing] = useState<ClientVehicle | 'new' | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const queryKey = ['vehicles', pumpId, clientId]
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey,
    queryFn: () => vehicleApi.getVehicles(pumpId, clientId),
  })

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['vehicles', pumpId] })
    qc.invalidateQueries({ queryKey: ['vehiclePicker', pumpId] })
  }

  const saveMutation = useMutation({
    mutationFn: (input: SaveVehicleInput) => editing && editing !== 'new'
      ? vehicleApi.updateVehicle(pumpId, clientId, editing.id, input)
      : vehicleApi.addVehicle(pumpId, clientId, input),
    onSuccess: (saved) => {
      refresh()
      setEditing(null)
      addToast(t('vehicles.saved', { registration: saved.registration }), 'success')
    },
    onError: (err) => setFormError(parseApiError(err, t('vehicles.saveFailed'))),
  })

  const statusMutation = useMutation({
    mutationFn: (vehicle: ClientVehicle) => vehicleApi.setVehicleActive(pumpId, clientId, vehicle.id, !vehicle.active),
    onSuccess: (saved) => {
      refresh()
      addToast(t(saved.active ? 'vehicles.reactivated' : 'vehicles.deactivated', { registration: saved.registration }), 'success')
    },
    onError: (err) => addToast(parseApiError(err, t('vehicles.saveFailed')), 'error'),
  })

  const checkMutation = useMutation({
    mutationFn: (mode: VehicleCheckMode) => vehicleApi.setVehicleCheck(pumpId, clientId, mode),
    onSuccess: (result) => {
      qc.setQueryData(queryKey, result)
      refresh()
      addToast(t('vehicles.check.saved', { mode: t(`vehicles.check.modes.${result.vehicleCheck}`) }), 'success')
    },
    onError: (err) => addToast(parseApiError(err, t('vehicles.check.saveFailed')), 'error'),
  })

  if (isLoading) return <SkeletonRows count={3} />
  if (isError || !data) return <LoadError onRetry={() => refetch()} message={t('vehicles.loadFailed')} />

  const isSubAccount = data.checkClientId !== clientId
  const showOwner = data.vehicles.some((vehicle) => vehicle.clientId !== clientId)
  const openForm = (vehicle: ClientVehicle | 'new') => { setFormError(null); setEditing(vehicle) }

  return (
    <div className="space-y-4">
      <VehicleCheckSetting mode={data.vehicleCheck} setOnParentName={isSubAccount ? data.checkClientName : null}
        isSaving={checkMutation.isPending} onChange={(mode) => checkMutation.mutate(mode)} />

      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-ink">{t('vehicles.title')}</h3>
        {editing === null && (
          <button type="button" onClick={() => openForm('new')} className="ui-btn ui-btn-primary ui-btn-compact">
            {t('vehicles.add')}
          </button>
        )}
      </div>

      {editing !== null && (
        <VehicleForm key={editing === 'new' ? 'new' : editing.id} vehicle={editing === 'new' ? null : editing}
          isSaving={saveMutation.isPending} error={formError}
          onSave={(input) => saveMutation.mutate(input)} onCancel={() => setEditing(null)} />
      )}

      {data.vehicles.length === 0 ? (
        <EmptyState icon="transactions" title={t('vehicles.empty')} />
      ) : (
        <ul className="ui-card divide-y divide-border p-0">
          {data.vehicles.map((vehicle) => (
            <VehicleCard key={vehicle.id} vehicle={vehicle} showOwner={showOwner}
              isBusy={statusMutation.isPending}
              onEdit={() => openForm(vehicle)} onToggleActive={() => statusMutation.mutate(vehicle)} />
          ))}
        </ul>
      )}
    </div>
  )
}
