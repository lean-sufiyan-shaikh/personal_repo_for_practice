import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Printer } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ShortageClaim } from '../../../api/inventoryApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { SidePanel } from '../../../components/layout/SidePanel'
import { printWhenFontsReady } from '../../../i18n/fonts'
import { useToastStore } from '../../../store/toastStore'
import { ClaimNoteBody } from './ClaimNoteBody'
import { RejectClaimDialog } from './RejectClaimDialog'
import { SettleClaimDialog } from './SettleClaimDialog'

interface Props {
  pumpId: number
  claimId: number
  canResolve: boolean
  onClose: () => void
}

/** One shortage claim with its printable claim note; an owner or admin settles or rejects it while it is raised. */
export function ClaimDetailPanel({ pumpId, claimId, canResolve, onClose }: Props) {
  const { t } = useTranslation('inventory')
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const [dialog, setDialog] = useState<'settle' | 'reject' | null>(null)

  const { data: claim, isLoading, isError, refetch } = useQuery({
    queryKey: ['shortageClaim', pumpId, claimId],
    queryFn: () => inventoryApi.getShortageClaim(pumpId, claimId),
  })

  useEffect(() => {
    document.body.classList.add('handover-printing')
    return () => document.body.classList.remove('handover-printing')
  }, [])

  const resolved = (updated: ShortageClaim, message: string) => {
    queryClient.setQueryData<ShortageClaim>(['shortageClaim', pumpId, claimId], {
      ...updated,
      nozzleReadings: updated.nozzleReadings ?? claim?.nozzleReadings ?? null,
      pump: updated.pump ?? claim?.pump ?? null,
    })
    void queryClient.invalidateQueries({ queryKey: ['shortageClaims', pumpId] })
    void queryClient.invalidateQueries({ queryKey: ['deliveries', pumpId] })
    setDialog(null)
    addToast(message, 'success')
  }

  const canAct = canResolve && claim?.status === 'RAISED'

  return (
    <SidePanel
      title={t('claimNote.panelTitle', { id: claimId })}
      subtitle={claim ? t('claimNote.panelSubtitle', { invoice: claim.invoiceReference, tank: claim.tankIdentifier }) : undefined}
      size="lg"
      onClose={onClose}
      headerClassName="print:hidden"
      bodyClassName="print:overflow-visible"
      footerClassName="print:hidden justify-end flex-wrap"
      headerActions={claim && (
        <button type="button" onClick={() => void printWhenFontsReady()} className="ui-btn ui-btn-secondary ui-btn-compact inline-flex items-center gap-1.5 text-xs">
          <Printer size={14} strokeWidth={2} aria-hidden="true" /> {t('claimNote.print')}
        </button>
      )}
      footer={canAct ? <>
        <button type="button" onClick={() => setDialog('reject')} className="ui-btn ui-btn-secondary">{t('claims.reject')}</button>
        <button type="button" onClick={() => setDialog('settle')} className="ui-btn ui-btn-primary">{t('claims.settle')}</button>
      </> : undefined}
    >
      {isLoading ? <SkeletonRows count={4} />
        : isError || !claim ? <LoadError onRetry={() => void refetch()} />
        : <ClaimNoteBody claim={claim} />}

      {claim && dialog === 'settle' && (
        <SettleClaimDialog pumpId={pumpId} claim={claim} onClose={() => setDialog(null)}
          onDone={updated => resolved(updated, t('settleClaim.done'))} />
      )}
      {claim && dialog === 'reject' && (
        <RejectClaimDialog pumpId={pumpId} claim={claim} onClose={() => setDialog(null)}
          onDone={updated => resolved(updated, t('rejectClaim.done'))} />
      )}
    </SidePanel>
  )
}
