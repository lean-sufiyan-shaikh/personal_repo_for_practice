import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Upload } from 'lucide-react'
import { bankReconciliationApi } from '../../api/bankReconciliationApi'
import type { StatementDateFormat, StatementImportSummary } from '../../api/bankReconciliationApi'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { PageSection } from '../../components/layout/PageSection'
import { parseApiError } from '../../utils/apiError'

const DATE_FORMATS: StatementDateFormat[] = ['AUTO', 'DAY_FIRST', 'MONTH_FIRST']

function isCsv(file: File): boolean {
  return file.name.toLowerCase().endsWith('.csv')
}

interface ImportStatementCardProps {
  pumpId: number
  onImported: (summary: StatementImportSummary) => void
}

/** Uploads a bank statement CSV (Date, Narration, Debit, Credit, Balance, UTR) for matching. */
export function ImportStatementCard({ pumpId, onImported }: ImportStatementCardProps) {
  const { t } = useTranslation(['reconciliation', 'common'])
  const [file, setFile] = useState<File | null>(null)
  const [fileKey, setFileKey] = useState(0)
  const [bankName, setBankName] = useState('')
  const [accountNumber, setAccountNumber] = useState('')
  const [dateFormat, setDateFormat] = useState<StatementDateFormat>('AUTO')
  const [error, setError] = useState<string | null>(null)

  const fileProblem = file && !isCsv(file) ? t('import.notCsv') : undefined
  const canImport = file !== null && !fileProblem && bankName.trim() !== ''

  const importMutation = useMutation({
    mutationFn: () => bankReconciliationApi.importStatement(pumpId, {
      file: file as File,
      bankName: bankName.trim(),
      accountNumber: accountNumber.trim() || undefined,
      dateFormat,
    }),
    onSuccess: (summary) => {
      setError(null)
      setFile(null)
      setFileKey((key) => key + 1)
      onImported(summary)
    },
    onError: (err: unknown) => setError(parseApiError(err, t('import.failed'))),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (canImport) importMutation.mutate()
  }

  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>

  return (
    <PageSection title={t('import.title')} description={t('import.description')}>
      <form onSubmit={submit} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label={t('import.file')} help={t('import.fileHelp')} error={fileProblem}>
            <input key={fileKey} type="file" accept=".csv,text/csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </FormField>
          <FormField label={t('import.bankName')}>
            <input type="text" value={bankName} maxLength={100} onChange={(e) => setBankName(e.target.value)} />
          </FormField>
          <FormField label={<>{t('import.accountNumber')} {optional}</>}>
            <input type="text" value={accountNumber} maxLength={50} onChange={(e) => setAccountNumber(e.target.value)} />
          </FormField>
          <FormField label={t('import.dateFormat')} help={t('import.dateFormatHelp')}>
            <select value={dateFormat} onChange={(e) => setDateFormat(e.target.value as StatementDateFormat)}>
              {DATE_FORMATS.map((format) => <option key={format} value={format}>{t(`import.dateFormats.${format}`)}</option>)}
            </select>
          </FormField>
        </div>
        {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
        <LoadingButton type="submit" disabled={!canImport} isLoading={importMutation.isPending} loadingLabel={t('import.importing')}
          className="ui-btn-primary min-h-11 w-full sm:w-auto">
          <Upload size={16} strokeWidth={2} aria-hidden="true" />
          {t('import.submit')}
        </LoadingButton>
      </form>
    </PageSection>
  )
}
