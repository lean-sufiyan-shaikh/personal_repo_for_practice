import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pettyCashApi } from '../../../api/pettyCashApi'
import type { PettyCashSettingsRequest, PettyCashSummary } from '../../../api/pettyCashApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { parseApiError } from '../../../utils/apiError'
import { roundToPaise } from '../../../utils/money'
import { useToastStore } from '../../../store/toastStore'

interface Props {
  pumpId: number
  summary: PettyCashSummary
  onClose: () => void
}

/** Owner/Admin: the float the box is refilled to and how far a count may differ before the owner is told. */
export function PettyCashSettingsDialog({ pumpId, summary, onClose }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const [floatAmount, setFloatAmount] = useState(String(summary.floatAmount))
  const [tolerance, setTolerance] = useState(String(summary.countTolerance))
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (request: PettyCashSettingsRequest) => pettyCashApi.updateSettings(pumpId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pettyCash', pumpId] })
      addToast(t('petty.settingsSaved'), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('petty.settingsFailed'))),
  })

  const floatValue = Number(floatAmount)
  const toleranceValue = Number(tolerance)
  const isValid = floatAmount.trim() !== '' && tolerance.trim() !== '' && floatValue >= 0 && toleranceValue >= 0

  return (
    <Modal title={t('petty.settingsTitle')} subtitle={t('petty.settingsSubtitle')} tone="info" size="md"
      onClose={onClose} canClose={!mutation.isPending} bodyClassName="space-y-4"
      footer={<>
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="button" disabled={!isValid} isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')}
          onClick={() => mutation.mutate({ floatAmount: roundToPaise(floatValue), countTolerance: roundToPaise(toleranceValue) })}
          className="ui-btn-primary min-h-11">
          {t('common:actions.save')}
        </LoadingButton>
      </>}>
      <FormField label={t('petty.floatLabel')} help={t('petty.floatHelp')}>
        <NumericInput unit="₹" min="0" step="0.01" value={floatAmount} onChange={e => setFloatAmount(e.target.value)} data-autofocus />
      </FormField>
      <FormField label={t('petty.toleranceLabel')}>
        <NumericInput unit="₹" min="0" step="0.01" value={tolerance} onChange={e => setTolerance(e.target.value)} />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
