import type { ImportRow } from '../../../api/importApi'
import type { ImportTemplate } from './importTemplates'

export type SheetProblem =
  | { code: 'tooBig' | 'notXlsx' | 'unreadable' | 'noRows' }
  | { code: 'missingColumns'; columns: string[] }
  | { code: 'tooManyRows'; maxRows: number }

export type SheetReadResult = { rows: ImportRow[] } | { problem: SheetProblem }

export type SheetCell = string | number | boolean | Date | null | undefined

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** Every cell as the text the server reads: dates as YYYY-MM-DD (Excel dates arrive as UTC midnight), yes/no for ticks. */
export function cellText(value: SheetCell): string {
  if (value === null || value === undefined) return ''
  if (value instanceof Date) return `${value.getUTCFullYear()}-${pad(value.getUTCMonth() + 1)}-${pad(value.getUTCDate())}`
  if (typeof value === 'boolean') return value ? 'yes' : 'no'
  return String(value).trim()
}

function normalisedHeader(value: SheetCell): string {
  return cellText(value).toLowerCase().replace(/\s+/g, '_')
}

/**
 * Turns the first sheet's rows into import rows: the first row names the columns (any order, extra columns ignored),
 * blank rows and the template's own example row are skipped, and each row keeps its Excel row number.
 */
export function rowsFromSheet(sheet: SheetCell[][], template: ImportTemplate): SheetReadResult {
  const [headerRow, ...dataRows] = sheet
  if (!headerRow) return { problem: { code: 'noRows' } }

  const headers = headerRow.map(normalisedHeader)
  const missing = template.columns.filter((column) => column.required && !headers.includes(column.header))
  if (missing.length > 0) return { problem: { code: 'missingColumns', columns: missing.map((column) => column.header) } }

  const rows: ImportRow[] = []
  dataRows.forEach((cells, index) => {
    const texts = Object.fromEntries(template.columns.map((column) => {
      const position = headers.indexOf(column.header)
      return [column.header, position >= 0 ? cellText(cells[position]) : '']
    }))
    const isBlank = Object.values(texts).every((value) => value === '')
    const isExample = template.columns.every((column) => texts[column.header] === column.example)
    if (isBlank || isExample) return
    const row: ImportRow = { rowNumber: index + 2 }
    template.columns.forEach((column) => { row[column.field] = texts[column.header] || null })
    rows.push(row)
  })

  if (rows.length === 0) return { problem: { code: 'noRows' } }
  if (rows.length > template.maxRows) return { problem: { code: 'tooManyRows', maxRows: template.maxRows } }
  return { rows }
}
