import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Ban, FileMinus, Landmark, Merge, RotateCcw, Undo2, Wallet } from 'lucide-react'
import type { CreditClient } from '../../../api/creditApi'
import { creditDeskApi } from '../../../api/creditDeskApi'
import { RowActionMenu } from '../../../components/RowActionMenu'
import type { RowActionMenuItem } from '../../../components/RowActionMenu'
import { canPerformAction } from '../../../permissions/permissions'
import type { UserRole } from '../../../types/auth'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { DeskActionModal } from './DeskActionModal'
import type { DeskActionKind } from './deskActions'
import { MergeClientModal } from './MergeClientModal'

const ITEM_CLASS = 'text-ink hover:bg-surface-sunken'
const DANGER_CLASS = 'text-bad hover:bg-bad-bg'

interface ClientDeskMenuProps {
  pumpId: number
  client: CreditClient
  allClients: CreditClient[]
  userRole: UserRole | undefined
}

/** Credit notes, deposits, advance refunds, write-off, closing and merging: whatever the role and the account allow. */
export function ClientDeskMenu({ pumpId, client, allClients, userRole }: ClientDeskMenuProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [open, setOpen] = useState(false)
  const [action, setAction] = useState<DeskActionKind | null>(null)
  const [merging, setMerging] = useState(false)
  const can = (permission: string) => canPerformAction(permission, userRole)
  const isMain = client.parentClientId == null
  const closed = client.closedAt != null
  const deposit = client.depositHeld ?? 0
  const advance = client.advance ?? 0

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
    qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId] })
    qc.invalidateQueries({ queryKey: ['creditClients', pumpId] })
  }
  const reopen = useMutation({
    mutationFn: () => creditDeskApi.reopenAccount(pumpId, client.id),
    onSuccess: () => { refresh(); addToast(t('desk.reopened'), 'success') },
    onError: (err) => addToast(parseApiError(err, t('desk.failed')), 'error'),
  })

  const icon = (Icon: typeof Ban) => <Icon size={14} strokeWidth={2} />
  const item = (key: DeskActionKind, Icon: typeof Ban, danger = false): RowActionMenuItem => ({
    key, label: t(`desk.${key}.title`), description: t(`desk.${key}.hint`), icon: icon(Icon),
    className: danger ? DANGER_CLASS : ITEM_CLASS, onSelect: () => setAction(key),
  })
  const items: RowActionMenuItem[] = closed ? [
    ...(can('credit:close-account') && client.mergedIntoClientId == null ? [{
      key: 'reopen', label: t('desk.reopen.title'), description: t('desk.reopen.hint'), icon: icon(RotateCcw),
      className: ITEM_CLASS, onSelect: () => reopen.mutate(), disabled: reopen.isPending,
    }] : []),
    ...(can('credit:manage-deposit') && deposit > 0 ? [item('refundDeposit', Undo2)] : []),
  ] : [
    ...(can('credit:issue-credit-note') ? [item('creditNote', FileMinus)] : []),
    ...(can('credit:record-deposit') ? [item('receiveDeposit', Landmark)] : []),
    ...(can('credit:manage-deposit') && deposit > 0 ? [item('applyDeposit', Wallet), item('refundDeposit', Undo2)] : []),
    ...(can('credit:refund-advance') && isMain && advance > 0 ? [item('refundAdvance', Undo2)] : []),
    ...(can('credit:write-off') && isMain && client.outstandingBalance > 0 ? [item('writeOff', Ban, true)] : []),
    ...(can('credit:merge-clients') ? [{
      key: 'merge', label: t('desk.merge.title'), description: t('desk.merge.hint'), icon: icon(Merge),
      className: ITEM_CLASS, onSelect: () => setMerging(true),
    }] : []),
    ...(can('credit:close-account') ? [item('closeAccount', Ban, true)] : []),
  ]

  if (items.length === 0) return null
  const available = action === 'refundAdvance' ? advance
    : action === 'applyDeposit' || action === 'refundDeposit' ? deposit
    : action === 'writeOff' || action === 'creditNote' ? Math.max(0, client.outstandingBalance)
    : undefined

  return (
    <div className="relative">
      <RowActionMenu
        label={t('desk.menuLabel')}
        open={open}
        onOpenChange={setOpen}
        triggerClassName="ui-btn ui-btn-secondary"
        menuClassName="absolute right-0 top-full z-6 mt-2 w-72 max-w-[calc(100vw-2rem)] overflow-hidden rounded-card border border-border bg-surface p-1.5 shadow-overlay"
        items={items.map(entry => ({ ...entry, onSelect: () => { setOpen(false); entry.onSelect() } }))}
      />
      {action && (
        <DeskActionModal kind={action} pumpId={pumpId} clientId={client.id} clientName={client.name}
          available={available}
          onDone={() => { refresh(); addToast(t(`desk.${action}.done`), 'success') }}
          onClose={() => setAction(null)} />
      )}
      {merging && (
        <MergeClientModal pumpId={pumpId} client={client} allClients={allClients}
          onMerged={() => { refresh(); addToast(t('desk.merge.done'), 'success') }}
          onClose={() => setMerging(false)} />
      )}
    </div>
  )
}
