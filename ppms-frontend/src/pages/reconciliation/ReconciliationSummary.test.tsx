import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { StatementImportSummary } from '../../api/bankReconciliationApi'
import { ReconciliationSummary } from './ReconciliationSummary'

const statement: StatementImportSummary = {
  id: 12, pumpId: 3, bankName: 'HDFC', accountNumber: '0042', statementFromDate: '2026-11-18', statementToDate: '2026-11-24',
  totalLines: 4, matchedLines: 1, suggestedLines: 1, ignoredLines: 0, unmatchedLines: 2,
  unmatchedCreditAmount: 2500, unmatchedDebitAmount: 800, importedByUserId: 1, importedAt: '2026-11-25T05:00:00Z',
  skippedDuplicateLines: null, skippedInvalidRows: null, autoMatchedLines: null, autoSuggestedLines: null,
}

describe('ReconciliationSummary', () => {
  it('shows the share matched, the count per status and the unmatched money in and out', () => {
    render(<ReconciliationSummary statement={statement} />)

    expect(screen.getByText('Share matched').nextSibling).toHaveTextContent('25%')
    expect(screen.getByText('Suggested').nextSibling).toHaveTextContent('1')
    expect(screen.getByText('Unmatched').nextSibling).toHaveTextContent('2')
    expect(screen.getByText('Unmatched money in').nextSibling).toHaveTextContent('₹2,500.00')
    expect(screen.getByText('Unmatched money out').nextSibling).toHaveTextContent('₹800.00')
  })

  it('shows 0% matched for a statement without lines', () => {
    render(<ReconciliationSummary statement={{ ...statement, totalLines: 0, matchedLines: 0 }} />)

    expect(screen.getByText('Share matched').nextSibling).toHaveTextContent('0%')
  })
})
