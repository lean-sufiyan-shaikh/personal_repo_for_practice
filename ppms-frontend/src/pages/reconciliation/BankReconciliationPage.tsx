import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { bankReconciliationApi } from '../../api/bankReconciliationApi'
import type { StatementImportSummary } from '../../api/bankReconciliationApi'
import { EmptyState } from '../../components/EmptyState'
import { LoadError } from '../../components/LoadError'
import { SkeletonRows } from '../../components/Skeleton'
import { Page } from '../../components/layout/Page'
import { PageHeader } from '../../components/layout/PageHeader'
import { useUrlParam } from '../../components/layout/useUrlParam'
import { usePumpStore } from '../../store/usePumpStore'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { ImportStatementCard } from './ImportStatementCard'
import { ImportResultNotice } from './ImportResultNotice'
import { ReconciliationSummary } from './ReconciliationSummary'
import { StatementImportPicker } from './StatementImportPicker'
import { StatementLinesSection } from './StatementLinesSection'

interface LastResult {
  kind: 'import' | 'autoMatch'
  summary: StatementImportSummary
}

/** Bank statement lines matched to settlements, deposits and payments; ?import=12 picks the statement shown. */
export default function BankReconciliationPage() {
  const { t } = useTranslation(['reconciliation', 'common'])
  const { selectedPumpId: pumpId } = usePumpStore()
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const selectedParam = useUrlParam('import')
  const [lastResult, setLastResult] = useState<LastResult | null>(null)

  const imports = useQuery({
    queryKey: ['reconciliation-imports', pumpId],
    queryFn: () => bankReconciliationApi.listImports(pumpId!),
    enabled: pumpId !== null,
  })

  const statements = imports.data ?? []
  const selected = statements.find((statement) => String(statement.id) === selectedParam.value) ?? statements[0]

  const refresh = (importId: number) => {
    queryClient.invalidateQueries({ queryKey: ['reconciliation-imports', pumpId] })
    queryClient.invalidateQueries({ queryKey: ['reconciliation-lines', pumpId, importId] })
  }

  const autoMatch = useMutation({
    mutationFn: (importId: number) => bankReconciliationApi.autoMatch(pumpId!, importId),
    onSuccess: (summary) => {
      refresh(summary.id)
      setLastResult({ kind: 'autoMatch', summary })
    },
    onError: (err: unknown) => addToast(parseApiError(err, t('lines.autoMatchFailed')), 'error'),
  })

  const imported = (summary: StatementImportSummary) => {
    refresh(summary.id)
    setLastResult({ kind: 'import', summary })
    selectedParam.set(String(summary.id))
  }

  if (pumpId === null) {
    return (
      <Page>
        <PageHeader title={t('page.title')} />
        <EmptyState icon="generic" title={t('common:notices.noPump')} />
      </Page>
    )
  }

  return (
    <Page>
      <PageHeader title={t('page.title')} description={t('page.subtitle')} />

      <ImportStatementCard pumpId={pumpId} onImported={imported} />

      {lastResult && (
        <ImportResultNotice result={lastResult.summary} kind={lastResult.kind} onDismiss={() => setLastResult(null)} />
      )}

      {imports.isLoading ? (
        <SkeletonRows count={3} />
      ) : imports.isError ? (
        <LoadError onRetry={() => void imports.refetch()} />
      ) : !selected ? (
        <EmptyState icon="transactions" title={t('imports.empty')} subtitle={t('imports.emptyHint')} />
      ) : (
        <>
          <StatementImportPicker imports={statements} selectedId={selected.id} onSelect={(id) => selectedParam.set(String(id))} />
          <ReconciliationSummary statement={selected} />
          <StatementLinesSection key={selected.id} pumpId={pumpId} statement={selected}
            isAutoMatching={autoMatch.isPending} onAutoMatch={() => autoMatch.mutate(selected.id)} />
        </>
      )}
    </Page>
  )
}
