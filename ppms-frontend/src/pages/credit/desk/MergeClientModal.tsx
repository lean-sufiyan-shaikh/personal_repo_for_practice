import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { CreditClient } from '../../../api/creditApi'
import { creditDeskApi } from '../../../api/creditDeskApi'
import { Modal } from '../../../components/Modal'
import { LoadingButton } from '../../../components/LoadingButton'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { parseApiError } from '../../../utils/apiError'

interface MergeClientModalProps {
  pumpId: number
  client: CreditClient
  allClients: CreditClient[]
  onMerged: () => void
  onClose: () => void
}

/** Only an open client on the same level can take the duplicate: both main accounts, or both under one parent. */
function mergeTargetsOf(client: CreditClient, allClients: CreditClient[]): CreditClient[] {
  return allClients.filter(candidate => candidate.id !== client.id
    && candidate.closedAt == null
    && (candidate.parentClientId ?? null) === (client.parentClientId ?? null))
}

/** Moves every record of this duplicate to the chosen client and closes the duplicate as merged. */
export function MergeClientModal({ pumpId, client, allClients, onMerged, onClose }: MergeClientModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const labelId = useId()
  const targets = mergeTargetsOf(client, allClients)
  const [targetId, setTargetId] = useState<string>('')
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditDeskApi.mergeInto(pumpId, client.id, Number(targetId)),
    onSuccess: () => {
      onMerged()
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('desk.failed'))),
  })

  return (
    <Modal
      title={t('desk.merge.title')}
      subtitle={client.name}
      tone="danger"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={(e) => { e.preventDefault(); if (targetId) mutation.mutate() }}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')}
            disabled={!targetId} className="ui-btn-danger">
            {t('desk.merge.confirm')}
          </LoadingButton>
        </>
      }
    >
      <p className="text-sm text-ink-2">{t('desk.merge.help', { name: client.name })}</p>
      {targets.length === 0 ? (
        <p className="ui-alert ui-alert-info text-sm">{t('desk.merge.noTargets')}</p>
      ) : (
        <div>
          <span id={labelId} className="ui-label">{t('desk.merge.target')}</span>
          <SearchableSelect aria-labelledby={labelId} value={targetId} onChange={setTargetId}
            options={targets.map(target => ({ value: String(target.id), label: target.name }))} />
        </div>
      )}
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
