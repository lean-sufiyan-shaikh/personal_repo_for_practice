import client from './client'

export type StatementDateFormat = 'AUTO' | 'DAY_FIRST' | 'MONTH_FIRST'

export type StatementLineStatus = 'UNMATCHED' | 'SUGGESTED' | 'MATCHED' | 'IGNORED'

export type MatchedRecordType = 'SETTLEMENT' | 'DEPOSIT' | 'CREDIT_PAYMENT' | 'SUPPLIER_PAYMENT' | 'SHIFT' | 'ANCILLARY_SALE'

export interface StatementImportSummary {
  id: number
  pumpId: number
  bankName: string
  accountNumber: string | null
  statementFromDate: string | null
  statementToDate: string | null
  totalLines: number
  matchedLines: number
  suggestedLines: number
  ignoredLines: number
  unmatchedLines: number
  unmatchedCreditAmount: number
  unmatchedDebitAmount: number
  importedByUserId: number
  importedAt: string
  /** Only filled on the import and auto-match responses. */
  skippedDuplicateLines: number | null
  skippedInvalidRows: number | null
  autoMatchedLines: number | null
  autoSuggestedLines: number | null
}

/** A settlement, deposit, payment, shift or sale a bank line is matched to or could be. */
export interface MatchedRecord {
  type: MatchedRecordType
  id: number
  date: string | null
  amount: number | null
  reference: string | null
  description: string | null
}

export interface StatementLine {
  id: number
  importId: number
  txnDate: string
  narration: string | null
  debitAmount: number | null
  creditAmount: number | null
  balance: number | null
  utrReference: string | null
  matchStatus: StatementLineStatus
  matchedShiftId: number | null
  matchedAncillarySaleId: number | null
  matchedPaymentId: number | null
  matchedSettlementId: number | null
  matchedDepositId: number | null
  matchedSupplierPaymentId: number | null
  /** The linked record when MATCHED, the suggestion when SUGGESTED, otherwise null. */
  matchedRecord: MatchedRecord | null
  matchNotes: string | null
  createdAt: string
}

export interface ImportStatementRequest {
  file: File
  bankName: string
  accountNumber?: string
  dateFormat: StatementDateFormat
}

export interface CandidateSearch {
  search?: string
  from?: string
  to?: string
}

/** Exactly one link is sent with MATCHED; matchNotes is the reason for IGNORED and explains an amount difference. */
export interface UpdateStatementLineRequest {
  status: 'MATCHED' | 'IGNORED' | 'UNMATCHED'
  matchedSettlementId?: number
  matchedDepositId?: number
  matchedPaymentId?: number
  matchedSupplierPaymentId?: number
  matchedShiftId?: number
  matchedSaleId?: number
  matchNotes?: string
}

const base = (pumpId: number) => `/pumps/${pumpId}/bank-reconciliation`

export const bankReconciliationApi = {
  /** Uploads a CSV statement; lines already imported before are skipped and counted, not refused. */
  importStatement: (pumpId: number, request: ImportStatementRequest): Promise<StatementImportSummary> => {
    const form = new FormData()
    form.append('file', request.file)
    form.append('bankName', request.bankName)
    if (request.accountNumber) form.append('accountNumber', request.accountNumber)
    form.append('dateFormat', request.dateFormat)
    // The client's JSON default would make axios turn the form into JSON; the browser adds the boundary itself.
    return client.post(`${base(pumpId)}/import`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
  },

  /** Imported statements, newest first. */
  listImports: (pumpId: number): Promise<StatementImportSummary[]> =>
    client.get(`${base(pumpId)}/imports`).then(r => r.data),

  getLines: (pumpId: number, importId: number): Promise<StatementLine[]> =>
    client.get(`${base(pumpId)}/imports/${importId}/lines`).then(r => r.data),

  /** Matches the statement again, e.g. after settlements or deposits were recorded. */
  autoMatch: (pumpId: number, importId: number): Promise<StatementImportSummary> =>
    client.post(`${base(pumpId)}/imports/${importId}/auto-match`).then(r => r.data),

  /** Records the line could be matched to; without dates the server looks 7 days before to 3 days after the line. */
  getCandidates: (pumpId: number, lineId: number, search: CandidateSearch): Promise<MatchedRecord[]> =>
    client.get(`${base(pumpId)}/lines/${lineId}/candidates`, { params: search }).then(r => r.data),

  updateLine: (pumpId: number, lineId: number, request: UpdateStatementLineRequest): Promise<StatementLine> =>
    client.patch(`${base(pumpId)}/lines/${lineId}`, request).then(r => r.data),

  acceptSuggestion: (pumpId: number, lineId: number): Promise<StatementLine> =>
    client.post(`${base(pumpId)}/lines/${lineId}/accept-suggestion`).then(r => r.data),
}
