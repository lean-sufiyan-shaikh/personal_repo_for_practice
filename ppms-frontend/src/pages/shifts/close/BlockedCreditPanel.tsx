import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import type { BlockedCreditClients } from './blockedCredit'

interface Props {
  clients: BlockedCreditClients
  /** Owners and admins may let the credit through with a reason; managers must ask them. */
  canAllow: boolean
  isSubmitting: boolean
  onAllow: (reason: string) => void
}

const GROUPS: Array<keyof BlockedCreditClients> = ['overLimitClients', 'onHoldClients', 'overdueClients']

/** Why the close was refused for credit, and what can be done about it. */
export function BlockedCreditPanel({ clients, canAllow, isSubmitting, onAllow }: Props) {
  const { t } = useTranslation('shifts')
  const [reason, setReason] = useState('')
  const [reasonMissing, setReasonMissing] = useState(false)

  const allow = () => {
    if (!reason.trim()) {
      setReasonMissing(true)
      return
    }
    onAllow(reason.trim())
  }

  return (
    <section role="alert" aria-label={t('close.blockedCredit.title')} className="ui-alert ui-alert-danger space-y-2 text-sm">
      <p className="font-semibold">{t('close.blockedCredit.title')}</p>
      <ul className="space-y-0.5">
        {GROUPS.filter((group) => clients[group] !== '—').map((group) => (
          <li key={group}>{t(`close.blockedCredit.${group}`, { names: clients[group] })}</li>
        ))}
      </ul>
      {canAllow ? (
        <div className="space-y-2">
          <FormField label={t('close.blockedCredit.reason')}
            error={reasonMissing ? t('close.blockedCredit.reasonRequired') : undefined}>
            <textarea rows={2} value={reason} className="resize-none text-sm"
              onChange={(e) => { setReason(e.target.value); setReasonMissing(false) }} />
          </FormField>
          <LoadingButton type="button" onClick={allow} isLoading={isSubmitting}
            loadingLabel={t('close.blockedCredit.allowing')} className="ui-btn-primary">
            {t('close.blockedCredit.allow')}
          </LoadingButton>
        </div>
      ) : (
        <p>{t('close.blockedCredit.askOwner')}</p>
      )}
    </section>
  )
}
