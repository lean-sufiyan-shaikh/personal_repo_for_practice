import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { pumpApi } from '../../api/pumpApi'
import type { CreditClient as PumpCreditClient } from '../../api/pumpApi'
import { KeyRound, Minus } from 'lucide-react'
import { ConfirmButton } from '../../components/ConfirmButton'
import { StatusBadge } from '../../components/StatusBadge'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { SetPortalPinModal } from './modals/SetPortalPinModal'

interface PortalPinControlProps {
  pumpId: number
  client: PumpCreditClient
}

export function PortalPinControl({ pumpId, client }: PortalPinControlProps) {
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [showPinModal, setShowPinModal] = useState(false)

  const removeMutation = useMutation({
    mutationFn: () => pumpApi.removeCreditClientPortalPin(pumpId, client.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['creditClients', pumpId] })
      addToast(`Portal PIN removed for ${client.name}`, 'success')
    },
    onError: (err) => addToast(parseApiError(err, 'Failed to remove portal PIN'), 'error'),
  })

  return (
    <>
      {client.hasPortalPin
        ? <StatusBadge tone="ok" icon={KeyRound} label="Portal PIN set" />
        : <StatusBadge tone="neutral" icon={Minus} label="No portal PIN" />}
      <button
        type="button"
        onClick={() => setShowPinModal(true)}
        className="ui-btn ui-btn-ghost min-h-0 px-2 py-1 text-xs text-primary hover:text-primary-strong border border-border"
      >
        {client.hasPortalPin ? 'Change PIN' : 'Set portal PIN'}
      </button>
      {client.hasPortalPin && (
        <ConfirmButton
          onConfirm={() => removeMutation.mutate()}
          disabled={removeMutation.isPending}
          triggerLabel="Remove PIN"
          triggerAriaLabel={`Remove portal PIN for ${client.name}`}
          prompt="Remove PIN?"
          triggerClassName="ui-btn ui-btn-ghost min-h-0 px-2 py-1 text-xs text-bad hover:text-bad-strong border border-bad/30"
        />
      )}
      {showPinModal && (
        <SetPortalPinModal pumpId={pumpId} client={client} onClose={() => setShowPinModal(false)} />
      )}
    </>
  )
}
