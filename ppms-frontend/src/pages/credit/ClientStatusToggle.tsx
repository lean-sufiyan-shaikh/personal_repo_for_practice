import { useTranslation } from 'react-i18next'
import type { CreditClient } from '../../api/pumpApi'
import { ConfirmButton } from '../../components/ConfirmButton'

const BUTTON_CLASS = 'ui-btn ui-btn-ghost min-h-0 px-2 py-1 text-xs border'

/** Disable asks for confirmation first; enable happens at once. */
export function ClientStatusToggle({ client, onChange, disabled }: {
  client: CreditClient
  onChange: (isActive: boolean) => void
  disabled: boolean
}) {
  const { t } = useTranslation(['credit', 'common'])

  if (client.isActive) {
    return (
      <ConfirmButton
        onConfirm={() => onChange(false)}
        disabled={disabled}
        triggerLabel={t('common:actions.disable')}
        triggerAriaLabel={t('clients.disableAria', { name: client.name })}
        prompt={t('clients.disablePrompt')}
        triggerClassName={`${BUTTON_CLASS} text-ink-3 hover:text-ink border-border`}
      />
    )
  }

  return (
    <button
      type="button"
      onClick={() => onChange(true)}
      disabled={disabled}
      className={`${BUTTON_CLASS} text-ok border-ok/30 hover:bg-ok-bg`}
    >
      {t('common:actions.enable')}
    </button>
  )
}
