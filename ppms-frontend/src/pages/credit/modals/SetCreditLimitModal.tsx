import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient } from '../../../api/creditApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { NumericInput } from '../../../components/NumericInput'
import { LoadingButton } from '../../../components/LoadingButton'
import { canPerformAction } from '../../../permissions/permissions'
import { useAuthStore } from '../../../store/authStore'
import { parseApiError } from '../../../utils/apiError'
import { formatCurrency } from '../../../utils/format'
import { CreditLimitFigures } from './CreditLimitFigures'
import { CreditLimitHistory } from './CreditLimitHistory'

interface SetCreditLimitModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

const MAX_REASON_LENGTH = 500

export function SetCreditLimitModal({ client, pumpId, onClose }: SetCreditLimitModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const role = useAuthStore(state => state.user?.role)
  const canSetUnlimited = canPerformAction('credit:set-unlimited-limit', role)
  const [unlimited, setUnlimited] = useState(client.unlimited)
  const [limit, setLimit] = useState(client.unlimited ? '' : String(client.creditLimit))
  const [reason, setReason] = useState('')
  const [limitError, setLimitError] = useState<string | undefined>()
  const [reasonError, setReasonError] = useState<string | undefined>()
  const [serverError, setServerError] = useState<string | null>(null)

  const { data: settings } = useQuery({
    queryKey: ['credit-limit-settings', pumpId],
    queryFn: () => creditApi.getCreditLimitSettings(pumpId),
    enabled: !canSetUnlimited,
  })
  const managerCap = canSetUnlimited ? null : settings?.managerLimitCap ?? null

  const mutation = useMutation({
    mutationFn: (creditLimit: number) =>
      creditApi.updateCreditLimit(pumpId, client.id, { creditLimit, unlimited, reason: reason.trim() }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-limit-changes', pumpId, client.id] })
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('creditLimit.failed'))),
  })

  const typedLimit = limit.trim() === '' ? NaN : Number(limit)
  const newLimit = unlimited ? null : Number.isFinite(typedLimit) && typedLimit >= 0 ? typedLimit : null

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const limitProblem = unlimited ? undefined
      : newLimit === null ? t('creditLimit.invalid')
      : managerCap !== null && newLimit > managerCap ? t('creditLimit.aboveCap', { cap: formatCurrency(managerCap) })
      : undefined
    const reasonProblem = reason.trim() === '' ? t('creditLimit.reasonRequired') : undefined
    setLimitError(limitProblem)
    setReasonError(reasonProblem)
    if (limitProblem || reasonProblem) return
    mutation.mutate(newLimit ?? 0)
  }

  return (
    <Modal
      title={t('creditLimit.title')}
      subtitle={client.name}
      tone="info"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')}
            className="ui-btn-primary">
            {t('creditLimit.save')}
          </LoadingButton>
        </>
      }
    >
      <label className="flex min-h-11 items-center gap-2 text-sm text-ink-2 select-none">
        <input type="checkbox" checked={unlimited} disabled={!canSetUnlimited}
          onChange={e => { setUnlimited(e.target.checked); setLimitError(undefined) }}
          className="rounded accent-primary w-4 h-4" />
        {t('creditLimit.unlimited')}
      </label>
      {!canSetUnlimited && <p className="text-xs text-ink-3">{t('creditLimit.unlimitedOwnerOnly')}</p>}

      {!unlimited && (
        <FormField label={t('creditLimit.label')} error={limitError}
          help={managerCap !== null ? t('creditLimit.managerCap', { cap: formatCurrency(managerCap) }) : t('creditLimit.help')}>
          <NumericInput unit="₹" min="0" step="0.01" value={limit}
            onChange={e => { setLimit(e.target.value); setLimitError(undefined) }}
            data-autofocus />
        </FormField>
      )}

      <FormField label={t('common:fields.reason')} error={reasonError}>
        <input value={reason} maxLength={MAX_REASON_LENGTH} placeholder={t('creditLimit.reasonPlaceholder')}
          onChange={e => { setReason(e.target.value); setReasonError(undefined) }} />
      </FormField>

      <CreditLimitFigures client={client} unlimited={unlimited} newLimit={newLimit} />

      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}

      <CreditLimitHistory pumpId={pumpId} clientId={client.id} />
    </Modal>
  )
}
