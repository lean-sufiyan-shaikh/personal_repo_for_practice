import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import type { CashDrop } from '../../../api/cashApi'
import { parseApiError } from '../../../utils/apiError'
import { useToastStore } from '../../../store/toastStore'
import { formatCurrency } from '../../../utils/format'

/** Everything that shows drops or the safe's money, refreshed after a drop is confirmed or rejected. */
export function refreshCashCustody(queryClient: ReturnType<typeof useQueryClient>, pumpId: number) {
  for (const key of ['cashDrops', 'pendingCashDrops', 'safe', 'safeLedger', 'cashEvents', 'attention']) {
    queryClient.invalidateQueries({ queryKey: [key, pumpId] })
  }
}

/** Confirm or reject one waiting drop; the drop being rejected is kept so its reason dialog can be shown. */
export function useReviewCashDrop(pumpId: number) {
  const { t } = useTranslation('cash')
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const [rejecting, setRejecting] = useState<CashDrop | null>(null)
  const [error, setError] = useState<string | null>(null)

  const confirm = useMutation({
    mutationFn: (drop: CashDrop) => cashApi.confirmDrop(pumpId, drop.id),
    onSuccess: (drop) => {
      setError(null)
      refreshCashCustody(queryClient, pumpId)
      addToast(t('drops.confirmed', { amount: formatCurrency(drop.amount), operator: drop.operatorName }), 'success')
    },
    onError: (err: unknown) => setError(parseApiError(err, t('drops.reviewFailed'))),
  })

  const reject = useMutation({
    mutationFn: ({ drop, reason }: { drop: CashDrop; reason: string }) => cashApi.rejectDrop(pumpId, drop.id, reason),
    onSuccess: (drop) => {
      setError(null)
      setRejecting(null)
      refreshCashCustody(queryClient, pumpId)
      addToast(t('drops.rejected', { amount: formatCurrency(drop.amount), operator: drop.operatorName }), 'info')
    },
    onError: (err: unknown) => setError(parseApiError(err, t('drops.reviewFailed'))),
  })

  return {
    confirm: (drop: CashDrop) => confirm.mutate(drop),
    confirmingId: confirm.isPending ? confirm.variables?.id ?? null : null,
    startReject: (drop: CashDrop) => { setError(null); setRejecting(drop) },
    cancelReject: () => { setError(null); setRejecting(null) },
    reject: (reason: string) => rejecting && reject.mutate({ drop: rejecting, reason }),
    rejecting,
    isRejecting: reject.isPending,
    error,
  }
}

export type CashDropReview = ReturnType<typeof useReviewCashDrop>
