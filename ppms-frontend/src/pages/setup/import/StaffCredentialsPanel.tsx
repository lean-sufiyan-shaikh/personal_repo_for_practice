import { useState } from 'react'
import { Download, KeyRound } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { StaffCredential } from '../../../api/importApi'
import { useImportTexts } from './importTexts'
import { downloadCredentials } from './spreadsheetFiles'

/**
 * The only time new staff passwords are available: the owner saves them as a sheet on their own device. They are not
 * listed on screen, so they cannot be read over the owner's shoulder or end up in a screenshot.
 */
export function StaffCredentialsPanel({ credentials }: { credentials: StaffCredential[] }) {
  const { t } = useTranslation('setup')
  const { credentialTexts } = useImportTexts()
  const [isSaved, setIsSaved] = useState(false)
  const [failed, setFailed] = useState(false)

  const save = async () => {
    setFailed(false)
    try {
      await downloadCredentials(credentials, credentialTexts)
      setIsSaved(true)
    } catch (error) {
      console.warn('Sign-in sheet could not be created', error instanceof Error ? error.name : 'unknown error')
      setFailed(true)
    }
  }

  return (
    <div role="region" aria-label={t('import.credentials.title')} className="ui-alert ui-alert-warning space-y-2">
      <p className="flex items-center gap-2 font-semibold">
        <KeyRound size={16} strokeWidth={2} aria-hidden="true" />
        {t('import.credentials.title')}
      </p>
      <p className="text-sm">{t('import.credentials.body')}</p>
      <button type="button" onClick={save} className="ui-btn ui-btn-primary inline-flex items-center gap-1.5">
        <Download size={14} strokeWidth={2} aria-hidden="true" />
        {t('import.credentials.download')}
      </button>
      {isSaved && <p role="status" className="text-sm">{t('import.credentials.downloaded')}</p>}
      {failed && <p role="alert" className="ui-error-text">{t('import.credentials.downloadFailed')}</p>}
    </div>
  )
}
