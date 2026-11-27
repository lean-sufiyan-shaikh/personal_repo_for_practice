import { useId, useState } from 'react'
import { Download, FileUp, RotateCcw, Upload } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { LoadingButton } from '../../../components/LoadingButton'
import { Spinner } from '../../../components/Spinner'
import { StatusBadge } from '../../../components/StatusBadge'
import type { ImportReport } from '../../../api/importApi'
import { ImportRowsTable } from './ImportRowsTable'
import type { ImportTemplate } from './importTemplates'
import { useImportTexts } from './importTexts'
import { downloadTemplate } from './spreadsheetFiles'
import { StaffCredentialsPanel } from './StaffCredentialsPanel'
import { useImportFlow } from './useImportFlow'

function ReportCounts({ report }: { report: ImportReport }) {
  const { t } = useTranslation('setup')
  return (
    <div className="flex flex-wrap gap-1.5">
      <StatusBadge tone="ok" label={t('import.counts.create', { count: report.createCount })} />
      <StatusBadge tone="neutral" label={t('import.counts.skip', { count: report.skipCount })} />
      {report.errorCount > 0 && <StatusBadge tone="bad" label={t('import.counts.error', { count: report.errorCount })} />}
    </div>
  )
}

/** Download the template, choose the filled-in file, check every row, then import it all at once. */
export function ImportFlow({ pumpId, template }: { pumpId: number; template: ImportTemplate }) {
  const { t } = useTranslation('setup')
  const { templateTexts } = useImportTexts()
  const { stage, problem, chooseFile, confirm, startOver, isImporting } = useImportFlow(pumpId, template)
  const [templateFailed, setTemplateFailed] = useState(false)
  const fileInputId = useId()
  const fileHintId = useId()

  const saveTemplate = async () => {
    setTemplateFailed(false)
    try {
      await downloadTemplate(template, templateTexts)
    } catch (error) {
      console.warn('Template could not be created', error instanceof Error ? error.name : 'unknown error')
      setTemplateFailed(true)
    }
  }

  if (stage.name === 'done') {
    return (
      <div className="space-y-3">
        <p role="status" className="text-sm font-semibold text-ok">
          {t('import.done.title')} · {t('import.done.summary', { created: stage.report.createCount, skipped: stage.report.skipCount })}
        </p>
        {stage.report.credentials.length > 0 && <StaffCredentialsPanel credentials={stage.report.credentials} />}
        <ImportRowsTable template={template} report={stage.report} rows={stage.rows} finished />
        <button type="button" onClick={startOver} className="ui-btn ui-btn-secondary inline-flex items-center gap-1.5">
          <RotateCcw size={14} strokeWidth={2} aria-hidden="true" />
          {t('import.done.another')}
        </button>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-ink">{t('import.stepDownload')}</h3>
        <button type="button" onClick={saveTemplate} className="ui-btn ui-btn-secondary inline-flex items-center gap-1.5">
          <Download size={14} strokeWidth={2} aria-hidden="true" />
          {t('import.downloadTemplate')}
        </button>
        {templateFailed && <p role="alert" className="ui-error-text">{t('import.downloadFailed')}</p>}
      </div>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-ink">{t('import.stepUpload')}</h3>
        <label htmlFor={fileInputId} className="ui-btn ui-btn-secondary inline-flex cursor-pointer items-center gap-1.5">
          <FileUp size={14} strokeWidth={2} aria-hidden="true" />
          {t('import.chooseFile')}
        </label>
        <input
          id={fileInputId}
          type="file"
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          aria-describedby={fileHintId}
          className="sr-only"
          disabled={stage.name === 'checking' || isImporting}
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) void chooseFile(file)
          }}
        />
        <p id={fileHintId} className="ui-help">{t('import.fileHint', { maxRows: template.maxRows })}</p>
        {stage.name === 'checking' && (
          <p role="status" className="flex items-center gap-2 text-sm text-ink-2"><Spinner />{t('import.reading')}</p>
        )}
        {problem && <p role="alert" className="ui-alert ui-alert-danger text-sm">{problem}</p>}
      </div>

      {stage.name === 'preview' && (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-ink">{t('import.stepCheck')}</h3>
          <ReportCounts report={stage.report} />
          {stage.report.errorCount > 0 && <p className="text-sm text-bad">{t('import.fixAndRetry')}</p>}
          {stage.report.errorCount === 0 && stage.report.createCount === 0 && <p className="text-sm text-ink-2">{t('import.nothingToAdd')}</p>}
          <ImportRowsTable template={template} report={stage.report} rows={stage.rows} />
          <div className="flex flex-wrap gap-2">
            <LoadingButton
              type="button"
              onClick={confirm}
              isLoading={isImporting}
              loadingLabel={t('import.importing')}
              disabled={stage.report.errorCount > 0 || stage.report.createCount === 0}
              className="ui-btn-primary inline-flex items-center gap-1.5"
            >
              <Upload size={14} strokeWidth={2} aria-hidden="true" />
              {t('import.confirm', { count: stage.report.createCount })}
            </LoadingButton>
            <button type="button" onClick={startOver} disabled={isImporting} className="ui-btn ui-btn-secondary">
              {t('import.chooseAnother')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
