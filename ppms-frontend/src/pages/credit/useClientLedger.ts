import { useMemo, useCallback } from 'react'
import { useQuery, useQueries } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { creditApi } from '../../api/creditApi'
import type { CreditTransaction, CreditExtension } from '../../api/creditApi'
import { pumpApi } from '../../api/pumpApi'
import type { MergedTx } from './ledgerGroups'

export function useClientLedger(pumpId: number, clientId: number, canApprovePayments: boolean) {
  // Subscribe to the ledger summary so it refreshes automatically after a payment
  const { data: allClients = [] } = useQuery({
    queryKey: ['credit-ledger', pumpId],
    queryFn: () => creditApi.getLedgerSummary(pumpId),
  })
  const client = allClients.find(c => c.id === clientId)

  // Full client list (needed to discover children, which may have 0 balance and not appear in ledger)
  const { data: pumpClientsData } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn:  () => pumpApi.getCreditClients(pumpId),
    enabled:  client?.isParent === true,
  })
  const childClients = useMemo(
    () => (pumpClientsData ?? []).filter(c => c.parentClientId === clientId),
    [pumpClientsData, clientId],
  )

  // Active credit extensions for this client
  const { data: activeExtensions = [] } = useQuery<CreditExtension[]>({
    queryKey: ['active-extensions', pumpId, clientId],
    queryFn: () => creditApi.getActiveExtensions(pumpId, clientId),
    enabled: !!client,
  })

  // The whole ledger is loaded so filters, month chips and week totals cover every row, not one page.
  const isParent = client?.isParent === true
  const { data: ownTransactions = [], isLoading } = useQuery({
    queryKey: ['credit-transactions', pumpId, clientId, 'all'],
    queryFn: () => creditApi.getAllTransactions(pumpId, clientId),
  })

  // outstandingInterest and totalInterestRecovered are now embedded in the client response —
  // no separate API call needed. Fall back to 0 while the client data is loading.
  const outstandingInterest   = client?.outstandingInterest   ?? 0
  const totalInterestRecovered = client?.totalInterestRecovered ?? 0

  const combineChildTransactions = useCallback(
    (results: UseQueryResult<CreditTransaction[]>[]) => ({
      childTxQueries: results,
      childTransactionsByIndex: results.map((result, i): MergedTx[] =>
        (result.data ?? []).map(tx => ({
          ...tx,
          subAccountName: childClients[i]?.name,
          sourceClientId: childClients[i]?.id ?? clientId,
        }))
      ),
    }),
    [childClients, clientId],
  )
  const { childTxQueries, childTransactionsByIndex } = useQueries({
    queries: childClients.map(child => ({
      queryKey: ['credit-transactions', pumpId, child.id, 'all'],
      queryFn:  () => creditApi.getAllTransactions(pumpId, child.id),
    })),
    combine: combineChildTransactions,
  })

  const { data: pendingPayments = [] } = useQuery({
    queryKey: ['credit-pending-payments', pumpId],
    queryFn: () => creditApi.getPendingPayments(pumpId),
    enabled: canApprovePayments,
  })
  const pendingPaymentRecorders = useMemo(
    () => new Map(pendingPayments.map(p => [p.id, p.recordedById])),
    [pendingPayments],
  )

  const transactions: MergedTx[] = useMemo(
    () => isParent
      ? [
          ...ownTransactions.map(tx => ({ ...tx, sourceClientId: clientId })),
          ...childTransactionsByIndex.flat(),
        ].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
      : ownTransactions.map(tx => ({ ...tx, sourceClientId: clientId })),
    [isParent, ownTransactions, childTransactionsByIndex, clientId],
  )

  return {
    allClients, client, childClients, activeExtensions, isParent, ownTransactions, isLoading,
    outstandingInterest, totalInterestRecovered, childTxQueries, childTransactionsByIndex,
    pendingPaymentRecorders, transactions,
  }
}
