import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { bankReconciliationApi } from '../../api/bankReconciliationApi'
import type { MatchedRecord, StatementLine } from '../../api/bankReconciliationApi'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { matchLinkFor } from './reconciliationFormat'

/** Accept, match, ignore and unmatch for the lines of one statement, and the line whose match or ignore dialog is open. */
export function useStatementLineActions(pumpId: number, importId: number) {
  const { t } = useTranslation('reconciliation')
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const [matching, setMatching] = useState<StatementLine | null>(null)
  const [ignoring, setIgnoring] = useState<StatementLine | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['reconciliation-lines', pumpId, importId] })
    queryClient.invalidateQueries({ queryKey: ['reconciliation-imports', pumpId] })
  }

  const closeDialogs = () => {
    setMatching(null)
    setIgnoring(null)
    setDialogError(null)
  }

  const accept = useMutation({
    mutationFn: (line: StatementLine) => bankReconciliationApi.acceptSuggestion(pumpId, line.id),
    onSuccess: () => { refresh(); addToast(t('actions.accepted'), 'success') },
    onError: (err: unknown) => addToast(parseApiError(err, t('actions.failed')), 'error'),
  })

  const unmatch = useMutation({
    mutationFn: (line: StatementLine) => bankReconciliationApi.updateLine(pumpId, line.id, { status: 'UNMATCHED' }),
    onSuccess: () => { refresh(); addToast(t('actions.unmatched'), 'success') },
    onError: (err: unknown) => addToast(parseApiError(err, t('actions.failed')), 'error'),
  })

  const ignore = useMutation({
    mutationFn: ({ line, reason }: { line: StatementLine; reason: string }) =>
      bankReconciliationApi.updateLine(pumpId, line.id, { status: 'IGNORED', matchNotes: reason }),
    onSuccess: () => { refresh(); closeDialogs(); addToast(t('actions.ignored'), 'success') },
    onError: (err: unknown) => setDialogError(parseApiError(err, t('actions.failed'))),
  })

  const match = useMutation({
    mutationFn: ({ line, record, note }: { line: StatementLine; record: MatchedRecord; note: string }) =>
      bankReconciliationApi.updateLine(pumpId, line.id, { status: 'MATCHED', ...matchLinkFor(record), matchNotes: note || undefined }),
    onSuccess: () => { refresh(); closeDialogs(); addToast(t('actions.matched'), 'success') },
    onError: (err: unknown) => setDialogError(parseApiError(err, t('actions.failed'))),
  })

  return {
    matching,
    ignoring,
    dialogError,
    isBusy: accept.isPending || unmatch.isPending,
    startMatch: (line: StatementLine) => { setDialogError(null); setMatching(line) },
    startIgnore: (line: StatementLine) => { setDialogError(null); setIgnoring(line) },
    closeDialogs,
    accept: (line: StatementLine) => accept.mutate(line),
    unmatch: (line: StatementLine) => unmatch.mutate(line),
    ignore: (reason: string) => { if (ignoring) ignore.mutate({ line: ignoring, reason }) },
    isIgnoring: ignore.isPending,
    match: (record: MatchedRecord, note: string) => { if (matching) match.mutate({ line: matching, record, note }) },
    isMatching: match.isPending,
  }
}

export type StatementLineActions = ReturnType<typeof useStatementLineActions>
