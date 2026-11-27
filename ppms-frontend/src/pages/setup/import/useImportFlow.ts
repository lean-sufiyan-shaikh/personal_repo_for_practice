import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { importApi, type ImportReport, type ImportRow } from '../../../api/importApi'
import { parseApiError } from '../../../utils/apiError'
import type { ImportTemplate } from './importTemplates'
import type { SheetProblem } from './sheetRows'
import { readImportFile } from './spreadsheetFiles'

export type ImportStage =
  | { name: 'choose' }
  | { name: 'checking' }
  | { name: 'preview'; rows: ImportRow[]; report: ImportReport }
  | { name: 'done'; rows: ImportRow[]; report: ImportReport }

/** Data each import type adds to the screens, refreshed once its import is done. */
const QUERIES_CHANGED_BY: Record<ImportTemplate['type'], string[]> = {
  staff: ['staff'],
  'credit-clients': ['creditClients', 'creditStanding', 'credit-ledger'],
  products: ['ancillaryProducts', 'ancillaryLots', 'ancillaryDeliveries'],
  'tank-stock': ['tanks', 'tankStocks'],
}

/** Choose a file → the server checks every row → the owner confirms → one all-or-nothing import. */
export function useImportFlow(pumpId: number, template: ImportTemplate) {
  const { t } = useTranslation('setup')
  const queryClient = useQueryClient()
  const [stage, setStage] = useState<ImportStage>({ name: 'choose' })
  const [problem, setProblem] = useState<string | null>(null)

  const describeProblem = (sheetProblem: SheetProblem) => {
    if (sheetProblem.code === 'missingColumns') return t('import.problems.missingColumns', { columns: sheetProblem.columns.join(', ') })
    if (sheetProblem.code === 'tooManyRows') return t('import.problems.tooManyRows', { maxRows: sheetProblem.maxRows })
    return t(`import.problems.${sheetProblem.code}`)
  }

  const chooseFile = async (file: File) => {
    setProblem(null)
    setStage({ name: 'checking' })
    try {
      const result = await readImportFile(file, template)
      if ('problem' in result) {
        setProblem(describeProblem(result.problem))
        setStage({ name: 'choose' })
        return
      }
      const report = await importApi.preview(pumpId, template.type, result.rows)
      setStage({ name: 'preview', rows: result.rows, report })
    } catch (error) {
      setProblem(parseApiError(error))
      setStage({ name: 'choose' })
    }
  }

  const importMutation = useMutation({
    mutationFn: (rows: ImportRow[]) => importApi.importRows(pumpId, template.type, rows),
    onSuccess: (report, rows) => {
      setStage({ name: 'done', rows, report })
      QUERIES_CHANGED_BY[template.type].forEach((key) => queryClient.invalidateQueries({ queryKey: [key] }))
      queryClient.invalidateQueries({ queryKey: ['setup-checklist'] })
    },
    onError: (error) => setProblem(parseApiError(error)),
  })

  const confirm = () => {
    if (stage.name !== 'preview') return
    setProblem(null)
    importMutation.mutate(stage.rows)
  }

  const startOver = () => {
    setProblem(null)
    setStage({ name: 'choose' })
  }

  return { stage, problem, chooseFile, confirm, startOver, isImporting: importMutation.isPending }
}
