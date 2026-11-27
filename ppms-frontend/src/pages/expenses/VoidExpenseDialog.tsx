import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { expenseApi } from '../../api/expenseApi'
import type { PumpExpense } from '../../api/expenseApi'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { codeLabel } from '../../constants/labels'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { formatCurrency } from '../../utils/format'

interface Props {
  pumpId: number
  expense: PumpExpense
  onClose: () => void
}

/** Owner/Admin voids an approved expense with a reason; the cash it took from the safe or petty cash goes back. */
export function VoidExpenseDialog({ pumpId, expense, onClose }: Props) {
  const { t } = useTranslation('expenses')
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [error, setError] = useState<string | null>(null)
  const mutation = useMutation({
    mutationFn: (reason: string) => expenseApi.voidExpense(pumpId, expense.id, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['expenses', pumpId] })
      qc.invalidateQueries({ queryKey: ['safe', pumpId] })
      qc.invalidateQueries({ queryKey: ['pettyCash', pumpId] })
      addToast(t('toast.voided'), 'success')
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('toast.voidFailed'))),
  })

  return (
    <RejectReasonDialog
      title={t('list.voidTitle')}
      subtitle={`${codeLabel(expense.category)} · ${formatCurrency(expense.amount)} · ${expense.description}`}
      help={expense.paidFrom === 'SAFE' || expense.paidFrom === 'PETTY_CASH'
        ? t('list.voidHelpCash', { source: codeLabel(expense.paidFrom) })
        : t('list.voidHelp')}
      confirmLabel={t('list.void')}
      pendingLabel={t('list.voiding')}
      isPending={mutation.isPending}
      error={error}
      onConfirm={(reason) => mutation.mutate(reason)}
      onClose={onClose}
    />
  )
}
