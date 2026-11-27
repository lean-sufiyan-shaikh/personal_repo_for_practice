import { useTranslation } from 'react-i18next'
import { KeyRound, Minus, ShieldAlert } from 'lucide-react'
import type { CreditClient } from '../../api/pumpApi'
import { StatusBadge } from '../../components/StatusBadge'

/** Whether the client can use the balance page, and a warning when their PIN is weak and should be replaced. */
export function PortalPinBadge({ client }: { client: Pick<CreditClient, 'hasPortalPin' | 'weakPortalPin'> }) {
  const { t } = useTranslation('credit')
  if (!client.hasPortalPin) return <StatusBadge tone="neutral" icon={Minus} label={t('portalPin.none')} />
  if (client.weakPortalPin) {
    return (
      <span title={t('portalPin.weakHint')}>
        <StatusBadge tone="warn" icon={ShieldAlert} label={t('portalPin.weak')} />
      </span>
    )
  }
  return <StatusBadge tone="ok" icon={KeyRound} label={t('portalPin.set')} />
}
