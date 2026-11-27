import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { SettlementPaymentType, UpdateConfigRequest } from '../../api/paymentSettlementApi'
import { SkeletonRows } from '../../components/Skeleton'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { typeLabel } from './settlementFormat'
import { SettlementConfigCard } from './SettlementConfigCard'

interface SettlementConfigTabProps {
  pumpId: number
  canConfigure: boolean
}

export function SettlementConfigTab({ pumpId, canConfigure }: SettlementConfigTabProps) {
  const { t } = useTranslation(['settlements', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [savingType, setSavingType] = useState<SettlementPaymentType | null>(null)

  const { data: configs = [], isLoading } = useQuery({
    queryKey: ['settlement-configs', pumpId],
    queryFn:  () => paymentSettlementApi.getConfigs(pumpId),
    enabled:  !!pumpId,
  })

  const saveConfig = async (type: SettlementPaymentType, request: UpdateConfigRequest): Promise<boolean> => {
    setSavingType(type)
    try {
      await paymentSettlementApi.upsertConfig(pumpId, type, request)
      await qc.invalidateQueries({ queryKey: ['settlement-configs', pumpId] })
      addToast(t('alerts.saved', { type: typeLabel(type) }), 'success')
      return true
    } catch (err) {
      addToast(parseApiError(err, t('alerts.saveFailed')), 'error')
      return false
    } finally {
      setSavingType(null)
    }
  }

  if (isLoading) return <SkeletonRows count={3} />

  return (
    <div className="space-y-3">
      {configs.map(config => (
        <SettlementConfigCard
          key={config.paymentType}
          config={config}
          canConfigure={canConfigure}
          isSaving={savingType === config.paymentType}
          onSave={request => saveConfig(config.paymentType, request)}
        />
      ))}
    </div>
  )
}
