import client from './client'

export type ImportType = 'staff' | 'credit-clients' | 'products' | 'tank-stock'

export type RowOutcome = 'CREATE' | 'SKIP' | 'ERROR'

export interface ImportIssue {
  /** The template header the problem is in; null for the whole row. */
  column: string | null
  /** An errors.json code, filled with params. */
  code: string
  params: Record<string, unknown>
}

export interface ImportRowResult {
  rowNumber: number
  outcome: RowOutcome
  issues: ImportIssue[]
}

/** Sign-in details of a staff member the import created; sent once and never again. */
export interface StaffCredential {
  rowNumber: number
  fullName: string
  phoneNumber: string
  employeeId: string
  temporaryPassword: string
}

export interface ImportReport {
  totalRows: number
  createCount: number
  skipCount: number
  errorCount: number
  imported: boolean
  rows: ImportRowResult[]
  credentials: StaffCredential[]
}

/** One spreadsheet row: its Excel row number and each cell as text, named as the import endpoint expects. */
export type ImportRow = { rowNumber: number } & Record<string, string | number | null>

/** Hashing a temporary password for each new staff member takes a moment per row. */
const IMPORT_TIMEOUT_MS = 60_000

export const importApi = {
  preview: (pumpId: number, type: ImportType, rows: ImportRow[]) =>
    client.post<ImportReport>(`/pumps/${pumpId}/imports/${type}/preview`, { rows }, { timeout: IMPORT_TIMEOUT_MS })
      .then((r) => r.data),

  importRows: (pumpId: number, type: ImportType, rows: ImportRow[]) =>
    client.post<ImportReport>(`/pumps/${pumpId}/imports/${type}`, { rows }, { timeout: IMPORT_TIMEOUT_MS })
      .then((r) => r.data),
}
