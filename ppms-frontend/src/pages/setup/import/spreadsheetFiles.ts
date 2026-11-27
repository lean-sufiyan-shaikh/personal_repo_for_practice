import type { StaffCredential } from '../../../api/importApi'
import { MAX_FILE_BYTES, templateFileName, type ImportTemplate, type TemplateColumn } from './importTemplates'
import { rowsFromSheet, type SheetCell, type SheetReadResult } from './sheetRows'

/** The words written into a template's instructions sheet, in the owner's language. */
export interface TemplateTexts {
  dataSheet: string
  instructionsSheet: string
  intro: string[]
  columnTitle: string
  requiredTitle: string
  helpTitle: string
  allowedTitle: string
  yes: string
  no: string
  help: (column: TemplateColumn) => string
}

export interface CredentialTexts {
  sheet: string
  warning: string
  headers: [string, string, string, string]
}

const bold = (value: string) => ({ value, fontWeight: 'bold' as const })

/** The libraries load only when an owner downloads or reads a file, keeping them out of the main bundle. */
export async function downloadTemplate(template: ImportTemplate, texts: TemplateTexts): Promise<void> {
  const { default: writeXlsxFile } = await import('write-excel-file/browser')
  const data = [
    template.columns.map((column) => bold(column.header)),
    template.columns.map((column) => column.example),
  ]
  const instructions = [
    ...texts.intro.map((line) => [line]),
    [],
    [bold(texts.columnTitle), bold(texts.requiredTitle), bold(texts.helpTitle), bold(texts.allowedTitle)],
    ...template.columns.map((column) => [
      column.header, column.required ? texts.yes : texts.no, texts.help(column), column.choices?.join(', ') ?? '',
    ]),
  ]
  await writeXlsxFile([
    { sheet: texts.dataSheet, data, columns: template.columns.map(() => ({ width: 20 })), stickyRowsCount: 1 },
    { sheet: texts.instructionsSheet, data: instructions, columns: [{ width: 24 }, { width: 12 }, { width: 70 }, { width: 40 }] },
  ]).toFile(templateFileName(template.type))
}

export async function readImportFile(file: File, template: ImportTemplate): Promise<SheetReadResult> {
  if (!file.name.toLowerCase().endsWith('.xlsx')) return { problem: { code: 'notXlsx' } }
  if (file.size > MAX_FILE_BYTES) return { problem: { code: 'tooBig' } }
  const { readSheet } = await import('read-excel-file/browser')
  try {
    return rowsFromSheet(await readSheet(file) as SheetCell[][], template)
  } catch (error) {
    console.warn('Import file could not be read', error instanceof Error ? error.name : 'unknown error')
    return { problem: { code: 'unreadable' } }
  }
}

/** The one copy of new staff members' temporary passwords, saved on the owner's own device. */
export async function downloadCredentials(credentials: StaffCredential[], texts: CredentialTexts): Promise<void> {
  const { default: writeXlsxFile } = await import('write-excel-file/browser')
  const data = [
    [bold(texts.warning)],
    [],
    texts.headers.map(bold),
    ...credentials.map((credential) => [
      credential.fullName, credential.phoneNumber, credential.employeeId, credential.temporaryPassword,
    ]),
  ]
  await writeXlsxFile(data, { sheet: texts.sheet, columns: [{ width: 28 }, { width: 16 }, { width: 18 }, { width: 22 }] })
    .toFile('ppms-staff-sign-in.xlsx')
}
