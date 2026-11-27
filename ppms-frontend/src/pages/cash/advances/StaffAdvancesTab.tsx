import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { staffAdvanceApi } from '../../../api/staffAdvanceApi'
import type { StaffAdvance } from '../../../api/staffAdvanceApi'
import { MoneyText } from '../../../components/AmountText'
import { RejectReasonDialog } from '../../../components/RejectReasonDialog'
import { SkeletonRows } from '../../../components/Skeleton'
import { StatusBadge } from '../../../components/StatusBadge'
import { parseApiError } from '../../../utils/apiError'
import { GiveAdvanceDialog } from './GiveAdvanceDialog'

/** Cash advances to staff: what each person still owes, every advance, and voiding one payroll has not touched. */
export function StaffAdvancesTab({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const [giving, setGiving] = useState(false)
  const [voiding, setVoiding] = useState<StaffAdvance | null>(null)
  const [voidError, setVoidError] = useState<string | null>(null)
  const { data, isLoading, error } = useQuery({
    queryKey: ['staffAdvances', pumpId],
    queryFn: () => staffAdvanceApi.list(pumpId),
  })
  const voidMutation = useMutation({
    mutationFn: ({ advance, reason }: { advance: StaffAdvance; reason: string }) => staffAdvanceApi.void(pumpId, advance.id, reason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['staffAdvances', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['cashEvents', pumpId] })
      setVoiding(null)
    },
    onError: (err: unknown) => setVoidError(parseApiError(err, t('advances.voidFailed'))),
  })

  return (
    <div className="space-y-(--density-gap)">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-ink-2">{t('advances.help')}</p>
        <button type="button" className="ui-btn ui-btn-primary" onClick={() => setGiving(true)}>{t('advances.give')}</button>
      </div>
      {isLoading && <SkeletonRows count={3} />}
      {error && <p role="alert" className="ui-alert ui-alert-warning text-sm">{t('advances.loadFailed')}</p>}
      {data && data.outstandingByStaff.some((row) => row.outstanding > 0) && (
        <section className="ui-card space-y-2" aria-label={t('advances.outstandingTitle')}>
          <h3 className="ui-title-sm">{t('advances.outstandingTitle')}</h3>
          <ul className="divide-y divide-border">
            {data.outstandingByStaff.filter((row) => row.outstanding > 0).map((row) => (
              <li key={row.staffUserId} className="flex justify-between py-1.5 text-sm">
                <span className="text-ink">{row.staffName}</span>
                <MoneyText value={row.outstanding} className="font-semibold text-ink" />
              </li>
            ))}
          </ul>
        </section>
      )}
      {data && data.advances.length === 0 && <div className="ui-card text-center text-sm text-ink-3">{t('advances.empty')}</div>}
      {data && data.advances.length > 0 && (
        <ul className="ui-card divide-y divide-border p-0">
          {data.advances.map((advance) => (
            <li key={advance.id} className="flex flex-wrap items-start justify-between gap-2 px-4 py-3 text-sm">
              <span className="min-w-0">
                <span className="block font-medium text-ink">{advance.staffName}</span>
                <span className="block text-xs text-ink-3">
                  {t('advances.line', { date: advance.advanceDate, by: advance.givenByName })}
                  {advance.note ? ` · ${advance.note}` : ''}
                </span>
                {advance.voidReason && <span className="block text-xs text-ink-3">{t('advances.voidedBecause', { reason: advance.voidReason })}</span>}
              </span>
              <span className="flex flex-col items-end gap-1">
                <MoneyText value={advance.amount} className="font-semibold text-ink" />
                {advance.status === 'VOIDED'
                  ? <StatusBadge tone="neutral" label={t('advances.voided')} />
                  : <span className="text-xs text-ink-3">{t('advances.outstanding')} <MoneyText value={advance.outstanding} /></span>}
                {advance.status === 'ACTIVE' && advance.recovered === 0 && (
                  <button type="button" className="ui-text-action" onClick={() => { setVoidError(null); setVoiding(advance) }}>
                    {t('advances.void')}
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {giving && <GiveAdvanceDialog pumpId={pumpId} onClose={() => setGiving(false)} />}
      {voiding && (
        <RejectReasonDialog
          title={t('advances.voidTitle')}
          subtitle={t('advances.voidSubtitle', { name: voiding.staffName })}
          isPending={voidMutation.isPending}
          error={voidError}
          confirmLabel={t('advances.void')}
          onConfirm={(reason) => voidMutation.mutate({ advance: voiding, reason })}
          onClose={() => setVoiding(null)}
        />
      )}
    </div>
  )
}
