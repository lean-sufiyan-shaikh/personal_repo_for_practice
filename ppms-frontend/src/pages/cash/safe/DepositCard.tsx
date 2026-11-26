import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { FileText, Paperclip } from 'lucide-react'
import { cashApi } from '../../../api/cashApi'
import type { BankDeposit } from '../../../api/cashApi'
import { MoneyText } from '../../../components/AmountText'
import { parseApiError } from '../../../utils/apiError'
import { useToastStore } from '../../../store/toastStore'
import { fmtDate } from '../cashFormat'
import { SLIP_ACCEPT, openSlip, slipFileProblem } from './slipFile'

interface Props {
  pumpId: number
  deposit: BankDeposit
  canAttach: boolean
}

/** One bank deposit as a phone card, with its slip to view or, when missing, to attach. */
export function DepositCard({ pumpId, deposit, canAttach }: Props) {
  const { t } = useTranslation('cash')
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const fileInputId = useId()
  const [error, setError] = useState<string | null>(null)

  const view = useMutation({
    mutationFn: () => cashApi.getDepositSlip(pumpId, deposit.id),
    onSuccess: (blob) => { setError(null); openSlip(blob) },
    onError: (err: unknown) => setError(parseApiError(err, t('safe.deposits.openFailed'))),
  })

  const attach = useMutation({
    mutationFn: (file: File) => cashApi.attachDepositSlip(pumpId, deposit.id, file),
    onSuccess: () => {
      setError(null)
      queryClient.invalidateQueries({ queryKey: ['bankDeposits', pumpId] })
      addToast(t('safe.deposits.slipAttached'), 'success')
    },
    onError: (err: unknown) => setError(parseApiError(err, t('safe.deposits.attachFailed'))),
  })

  const chooseFile = (file: File | undefined) => {
    if (!file) return
    const problem = slipFileProblem(file)
    if (problem) {
      setError(problem)
      return
    }
    attach.mutate(file)
  }

  return (
    <li className="ui-record-row flex-wrap gap-y-2">
      <div className="min-w-0 ui-record-row__main">
        <div className="flex flex-wrap items-baseline gap-x-2">
          <MoneyText value={deposit.amount} className="text-base font-semibold text-ink" />
          <span className="text-sm text-ink-2">{deposit.bankAccount}</span>
        </div>
        <p className="text-xs text-ink-3">
          {fmtDate(deposit.depositDate)} · {t('safe.deposits.slipReference', { reference: deposit.slipReference })}
          {' · '}{t('safe.deposits.recordedBy', { name: deposit.recordedByName })}
        </p>
        {deposit.notes && <p className="text-xs text-ink-2">{deposit.notes}</p>}
        {error && <p role="alert" className="text-xs text-bad">{error}</p>}
      </div>
      {deposit.hasSlipPhoto ? (
        <button type="button" onClick={() => view.mutate()} disabled={view.isPending}
          className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center gap-1.5">
          <FileText size={16} aria-hidden="true" />{t('safe.deposits.viewSlip')}
        </button>
      ) : canAttach && (
        <label htmlFor={fileInputId} aria-busy={attach.isPending || undefined}
          className="ui-btn ui-btn-secondary min-h-11 inline-flex cursor-pointer items-center gap-1.5">
          <Paperclip size={16} aria-hidden="true" />
          {attach.isPending ? t('safe.deposits.attaching') : t('safe.deposits.attachSlip')}
          <input id={fileInputId} type="file" accept={SLIP_ACCEPT} className="sr-only" disabled={attach.isPending}
            onChange={(event) => { chooseFile(event.target.files?.[0]); event.target.value = '' }} />
        </label>
      )}
    </li>
  )
}
