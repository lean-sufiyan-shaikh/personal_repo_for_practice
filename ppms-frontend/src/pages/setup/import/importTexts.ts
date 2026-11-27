import { useTranslation } from 'react-i18next'
import type { ImportIssue } from '../../../api/importApi'
import { translatedParams } from '../../../utils/apiError'
import type { CredentialTexts, TemplateTexts } from './spreadsheetFiles'

/** The words for spreadsheets and problems on the import screen, in the owner's language. */
export function useImportTexts() {
  const { t } = useTranslation(['setup', 'common', 'errors'])

  const columnLabel = (header: string) => t(`setup:import.columns.${header}`, { defaultValue: header })

  const issueText = (issue: ImportIssue) => {
    const message = t(`errors:${issue.code}`, { ...translatedParams(issue.params), defaultValue: issue.code })
    return `${issue.column ? columnLabel(issue.column) : t('setup:import.wholeRow')}: ${message}`
  }

  const templateTexts: TemplateTexts = {
    dataSheet: t('setup:import.template.dataSheet'),
    instructionsSheet: t('setup:import.template.instructionsSheet'),
    intro: [t('setup:import.template.intro1'), t('setup:import.template.intro2'), t('setup:import.template.intro3')],
    columnTitle: t('setup:import.template.column'),
    requiredTitle: t('setup:import.template.required'),
    helpTitle: t('setup:import.template.help'),
    allowedTitle: t('setup:import.template.allowed'),
    yes: t('common:actions.yes'),
    no: t('common:actions.no'),
    help: (column) => `${columnLabel(column.header)}. ${t(`setup:import.columnHelp.${column.header}`, { defaultValue: '' })}`,
  }

  const credentialTexts: CredentialTexts = {
    sheet: t('setup:import.credentials.sheet'),
    warning: t('setup:import.credentials.warning'),
    headers: [
      t('setup:import.credentials.name'),
      t('setup:import.credentials.phone'),
      t('setup:import.credentials.employeeId'),
      t('setup:import.credentials.password'),
    ],
  }

  return { columnLabel, issueText, templateTexts, credentialTexts }
}
