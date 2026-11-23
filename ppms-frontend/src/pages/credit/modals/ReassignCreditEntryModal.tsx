import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pumpApi } from '../../../api/pumpApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'

// ─── Reassign Credit Entry Modal ─────────────────────────────────────────────

interface ReassignModalProps {
  entryId: number
  currentClientId: number
  pumpId: number
  transactionType: 'SALE' | 'PAYMENT'
  onClose: () => void
}

export function ReassignCreditEntryModal({ entryId, currentClientId, pumpId, transactionType, onClose }: ReassignModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const [toClientId, setToClientId] = useState<string>('')
  const [reason, setReason]         = useState('')
  const [error, setError]           = useState<string | null>(null)
  const isPayment = transactionType === 'PAYMENT'

  const { data: allClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn:  () => pumpApi.getCreditClientAccounts(pumpId),
  })

  // Restrict targets to within the same parent/child group only:
  // • Parent account   → can move only to its own sub-accounts
  // • Sub-account      → can move to its parent OR sibling sub-accounts
  // • Standalone       → no valid targets (no group to move within)
  const currentClient = allClients.find(c => c.id === currentClientId)

  let targets: typeof allClients = []
  let noTargetsReason: string | null = null

  if (currentClient?.isParent) {
    targets = allClients.filter(c => c.parentClientId === currentClientId)
    if (targets.length === 0) {
      noTargetsReason = t('reassign.noSubAccounts')
    }
  } else if (currentClient?.parentClientId !== null && currentClient?.parentClientId !== undefined) {
    targets = allClients.filter(c =>
      c.id !== currentClientId &&
      (c.id === currentClient.parentClientId || c.parentClientId === currentClient.parentClientId)
    )
  } else {
    noTargetsReason = t('reassign.standalone')
  }

  const mutation = useMutation({
    mutationFn: ({ toId, rsn }: { toId: number; rsn: string }) =>
      isPayment
        ? pumpApi.reassignCreditPayment(pumpId, entryId, toId, rsn)
        : pumpApi.reassignCreditEntry(pumpId, entryId, toId, rsn),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, isPayment ? t('reassign.failedPayment') : t('reassign.failedEntry'))),
  })

  const handleSubmit = () => {
    if (!toClientId)        { setError(t('reassign.selectTarget')); return }
    if (!reason.trim())     { setError(t('reassign.reasonRequired')); return }
    setError(null)
    mutation.mutate({ toId: Number(toClientId), rsn: reason.trim() })
  }

  return (
    <Modal
      title={t('reassign.title')}
      subtitle={isPayment ? t('reassign.subtitlePayment') : t('reassign.subtitleSale')}
      tone="info"
      size="sm"
      onClose={onClose}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose}
            className="ui-btn ui-btn-secondary flex-1">
            {noTargetsReason ? t('common:actions.close') : t('common:actions.cancel')}
          </button>
          {!noTargetsReason && (
            <button onClick={handleSubmit} disabled={mutation.isPending}
              className="ui-btn ui-btn-primary flex-1">
              {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('reassign.moving')}</span> : t('reassign.submit')}
            </button>
          )}
        </>
      }
    >
      {noTargetsReason ? (
        <div className="ui-alert ui-alert-warning">
          <p className="text-sm text-warn">{noTargetsReason}</p>
        </div>
      ) : (
        <>
          <div className="ui-inline-form">
            <span className="ui-label">
              {t('reassign.moveTo')} <span className="text-bad">*</span>
            </span>
            <SearchableSelect label={t('reassign.moveTo')}
              value={toClientId}
              onChange={setToClientId}
              placeholder={t('reassign.targetPlaceholder')}
              searchThreshold={0}
              options={targets.map(c => ({
                value: String(c.id),
                label: c.parentClientId === null ? t('reassign.parentOption', { name: c.name }) : c.name,
              }))}
            />
          </div>
          <div className="ui-inline-form">
            <label className="block">
              <span className="ui-label">
              {t('form.reason')} <span className="text-bad">*</span>
            </span>
              <textarea
                value={reason}
                onChange={e => setReason(e.target.value)}
                rows={2}
                placeholder={isPayment ? t('reassign.reasonPlaceholderPayment') : t('reassign.reasonPlaceholderSale')}
                className="resize-none text-sm"
              />
            </label>
          </div>
          {error && <p role="alert" className="ui-error-text text-sm">{error}</p>}
        </>
      )}
    </Modal>
  )
}
