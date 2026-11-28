import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { StatementImportSummary } from '../../api/bankReconciliationApi'
import { ImportResultNotice } from './ImportResultNotice'

const statement: StatementImportSummary = {
  id: 12, pumpId: 3, bankName: 'HDFC', accountNumber: '0042', statementFromDate: '2026-11-18', statementToDate: '2026-11-24',
  totalLines: 4, matchedLines: 1, suggestedLines: 1, ignoredLines: 0, unmatchedLines: 2,
  unmatchedCreditAmount: 2500, unmatchedDebitAmount: 800, importedByUserId: 1, importedAt: '2026-11-25T05:00:00Z',
  skippedDuplicateLines: null, skippedInvalidRows: null, autoMatchedLines: null, autoSuggestedLines: null,
}

describe('ImportResultNotice', () => {
  it('says how many lines an import added, matched, suggested and skipped', async () => {
    const onDismiss = vi.fn()
    render(<ImportResultNotice kind="import" onDismiss={onDismiss} result={{
      ...statement, totalLines: 12, autoMatchedLines: 5, autoSuggestedLines: 3, skippedDuplicateLines: 2, skippedInvalidRows: 0,
    }} />)

    const notice = screen.getByRole('status')
    expect(notice).toHaveTextContent('Statement imported')
    expect(notice).toHaveTextContent('12 lines added · 5 matched · 3 suggested · 2 lines already imported, skipped')
    await userEvent.setup().click(screen.getByRole('button'))
    expect(onDismiss).toHaveBeenCalledOnce()
  })

  it('says only what an auto-match matched and suggested', () => {
    render(<ImportResultNotice kind="autoMatch" onDismiss={vi.fn()} result={{ ...statement, autoMatchedLines: 1, autoSuggestedLines: 0 }} />)

    expect(screen.getByRole('status')).toHaveTextContent('Auto-match finished1 matched · 0 suggested')
  })
})
