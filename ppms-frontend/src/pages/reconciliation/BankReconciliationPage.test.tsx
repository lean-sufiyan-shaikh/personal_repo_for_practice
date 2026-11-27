import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { bankReconciliationApi } from '../../api/bankReconciliationApi'
import type { MatchedRecord, StatementImportSummary, StatementLine } from '../../api/bankReconciliationApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import BankReconciliationPage from './BankReconciliationPage'

vi.mock('../../api/bankReconciliationApi', () => ({
  bankReconciliationApi: {
    importStatement: vi.fn(), listImports: vi.fn(), getLines: vi.fn(), autoMatch: vi.fn(),
    getCandidates: vi.fn(), updateLine: vi.fn(), acceptSuggestion: vi.fn(),
  },
}))

const statement: StatementImportSummary = {
  id: 12, pumpId: 3, bankName: 'HDFC', accountNumber: '0042', statementFromDate: '2026-11-18', statementToDate: '2026-11-24',
  totalLines: 4, matchedLines: 1, suggestedLines: 1, ignoredLines: 0, unmatchedLines: 2,
  unmatchedCreditAmount: 2500, unmatchedDebitAmount: 800, importedByUserId: 1, importedAt: '2026-11-25T05:00:00Z',
  skippedDuplicateLines: null, skippedInvalidRows: null, autoMatchedLines: null, autoSuggestedLines: null,
}

const settlementRecord: MatchedRecord = { type: 'SETTLEMENT', id: 31, date: '2026-11-20', amount: 4404.42, reference: 'UTR778812', description: 'Card settlement' }

function line(overrides: Partial<StatementLine>): StatementLine {
  return {
    id: 1, importId: 12, txnDate: '2026-11-20', narration: 'NEFT CR', debitAmount: null, creditAmount: 1000, balance: null,
    utrReference: null, matchStatus: 'UNMATCHED', matchedShiftId: null, matchedAncillarySaleId: null, matchedPaymentId: null,
    matchedSettlementId: null, matchedDepositId: null, matchedSupplierPaymentId: null, matchedChargebackId: null, matchedRecord: null, matchNotes: null,
    createdAt: '2026-11-25T05:00:00Z', ...overrides,
  }
}

const lines: StatementLine[] = [
  line({ id: 1, narration: 'CARD SETTLEMENT HDFC', creditAmount: 4404.42, utrReference: 'UTR778812', matchStatus: 'SUGGESTED', matchedRecord: settlementRecord }),
  line({ id: 2, narration: 'CASH DEPOSIT', creditAmount: 2500, matchStatus: 'UNMATCHED' }),
  line({ id: 3, narration: 'SMS CHARGES', creditAmount: null, debitAmount: 800, matchStatus: 'UNMATCHED' }),
  line({ id: 4, narration: 'UPI SETTLEMENT', creditAmount: 9000, matchStatus: 'MATCHED', matchedSettlementId: 30,
    matchedRecord: { ...settlementRecord, id: 30, amount: 9000, description: 'UPI settlement' } }),
]

function cardFor(narration: string): HTMLElement {
  const card = screen.getAllByTestId('statement-line-card').find((item) => within(item).queryByText(narration))
  if (!card) throw new Error(`No card for ${narration}`)
  return card
}

describe('BankReconciliationPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(bankReconciliationApi.listImports).mockResolvedValue([statement])
    vi.mocked(bankReconciliationApi.getLines).mockResolvedValue(lines)
    vi.mocked(bankReconciliationApi.updateLine).mockResolvedValue(lines[1])
    vi.mocked(bankReconciliationApi.acceptSuggestion).mockResolvedValue({ ...lines[0], matchStatus: 'MATCHED' })
  })

  it('imports a CSV statement and reports what was matched and skipped', async () => {
    const user = userEvent.setup()
    vi.mocked(bankReconciliationApi.listImports).mockResolvedValue([])
    vi.mocked(bankReconciliationApi.importStatement).mockResolvedValue({
      ...statement, totalLines: 12, autoMatchedLines: 5, autoSuggestedLines: 3, skippedDuplicateLines: 2, skippedInvalidRows: 0,
    })
    renderWithProviders(<BankReconciliationPage />)

    expect(await screen.findByText('No statements imported yet')).toBeInTheDocument()
    const importButton = screen.getByRole('button', { name: 'Import statement' })
    expect(importButton).toBeDisabled()

    const file = new File(['Date,Narration,Debit,Credit,Balance,UTR'], 'hdfc-nov.csv', { type: 'text/csv' })
    await user.upload(screen.getByLabelText('Statement file (.csv)'), file)
    await user.type(screen.getByLabelText('Bank name'), 'HDFC')
    await user.selectOptions(screen.getByLabelText('Date format'), 'DAY_FIRST')
    await user.click(importButton)

    expect(bankReconciliationApi.importStatement).toHaveBeenCalledWith(3, { file, bankName: 'HDFC', accountNumber: undefined, dateFormat: 'DAY_FIRST' })
    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent('Statement imported')
    expect(notice).toHaveTextContent('12 lines added · 5 matched · 3 suggested · 2 lines already imported, skipped')
  })

  it('refuses a file that is not a CSV', async () => {
    const user = userEvent.setup({ applyAccept: false })
    renderWithProviders(<BankReconciliationPage />)

    await user.upload(await screen.findByLabelText('Statement file (.csv)'), new File(['x'], 'statement.pdf', { type: 'application/pdf' }))
    await user.type(screen.getByLabelText('Bank name'), 'HDFC')

    expect(screen.getByText('Choose a .csv file.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import statement' })).toBeDisabled()
  })

  it('sums up the newest statement: share matched, count per status and unmatched money in and out', async () => {
    renderWithProviders(<BankReconciliationPage />)

    const summary = await screen.findByRole('group', { name: 'Reconciliation summary' })
    expect(within(summary).getByText('Share matched').nextSibling).toHaveTextContent('25%')
    expect(within(summary).getByText('Suggested').nextSibling).toHaveTextContent('1')
    expect(within(summary).getByText('Unmatched').nextSibling).toHaveTextContent('2')
    expect(within(summary).getByText('Unmatched money in').nextSibling).toHaveTextContent('₹2,500.00')
    expect(within(summary).getByText('Unmatched money out').nextSibling).toHaveTextContent('₹800.00')
    expect(bankReconciliationApi.getLines).toHaveBeenCalledWith(3, 12)
  })

  it('shows lines as cards on phones and as a table from md up, filtered by status', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BankReconciliationPage />)

    const cards = await screen.findAllByTestId('statement-line-card')
    expect(cards).toHaveLength(4)
    expect(cards[0].closest('ul')).toHaveClass('md:hidden')
    expect(screen.getByRole('region', { name: 'Bank statement lines' })).toHaveClass('hidden', 'md:block')

    const suggested = cardFor('CARD SETTLEMENT HDFC')
    expect(within(suggested).getByText('Suggested')).toBeInTheDocument()
    expect(within(suggested).getByText(/^Suggested: Settlement · 20 Nov 2026 · ₹4,404.42 · UTR778812$/)).toBeInTheDocument()
    expect(within(cardFor('SMS CHARGES')).getByText('−₹800.00')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Unmatched (2)' }))
    expect(screen.getAllByTestId('statement-line-card')).toHaveLength(2)
    expect(screen.queryByText('UPI SETTLEMENT')).not.toBeInTheDocument()
  })

  it('accepts a suggestion and refreshes the statement', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BankReconciliationPage />)

    await screen.findAllByTestId('statement-line-card')
    await user.click(within(cardFor('CARD SETTLEMENT HDFC')).getByRole('button', { name: /^Accept suggestion for line of 20 Nov 2026 for ₹4,404.42$/ }))

    expect(bankReconciliationApi.acceptSuggestion).toHaveBeenCalledWith(3, 1)
    await waitFor(() => expect(bankReconciliationApi.listImports).toHaveBeenCalledTimes(2))
  })

  it('matches a line by hand from the candidates and asks why when the amounts differ', async () => {
    const user = userEvent.setup()
    const deposit: MatchedRecord = { type: 'DEPOSIT', id: 5, date: '2026-11-19', amount: 2400, reference: 'SLIP-9', description: 'HDFC 0042' }
    vi.mocked(bankReconciliationApi.getCandidates).mockResolvedValue([deposit])
    renderWithProviders(<BankReconciliationPage />)

    await screen.findAllByTestId('statement-line-card')
    await user.click(within(cardFor('CASH DEPOSIT')).getByRole('button', { name: /^Match line of/ }))

    const dialog = await screen.findByRole('dialog', { name: 'Match bank line' })
    expect(dialog).toHaveTextContent('Money in on 20 Nov 2026: ₹2,500.00')
    await waitFor(() => expect(bankReconciliationApi.getCandidates).toHaveBeenCalledWith(3, 2, { search: undefined, from: undefined, to: undefined }))

    await user.type(within(dialog).getByLabelText('Search by amount or reference'), 'SLIP')
    await waitFor(() => expect(bankReconciliationApi.getCandidates).toHaveBeenLastCalledWith(3, 2, { search: 'SLIP', from: undefined, to: undefined }))

    await user.click(await within(dialog).findByRole('radio', { name: /Bank deposit/ }))
    expect(within(dialog).getByText('The line is ₹2,500.00 but the record is ₹2,400.00.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Match line' }))
    expect(within(dialog).getByText('Say why the amounts differ.')).toBeInTheDocument()
    expect(bankReconciliationApi.updateLine).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText('Why the amounts differ'), 'Bank took ₹100 cash handling')
    await user.click(within(dialog).getByRole('button', { name: 'Match line' }))
    expect(bankReconciliationApi.updateLine).toHaveBeenCalledWith(3, 2, {
      status: 'MATCHED', matchedDepositId: 5, matchNotes: 'Bank took ₹100 cash handling',
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('ignores a line only with a reason', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BankReconciliationPage />)

    await screen.findAllByTestId('statement-line-card')
    await user.click(within(cardFor('SMS CHARGES')).getByRole('button', { name: /^Ignore line of/ }))

    const dialog = await screen.findByRole('dialog', { name: 'Ignore bank line' })
    await user.click(within(dialog).getByRole('button', { name: 'Ignore line' }))
    expect(within(dialog).getByText('Say why this line is ignored.')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Reason'), 'Bank SMS charges')
    await user.click(within(dialog).getByRole('button', { name: 'Ignore line' }))
    expect(bankReconciliationApi.updateLine).toHaveBeenCalledWith(3, 3, { status: 'IGNORED', matchNotes: 'Bank SMS charges' })
  })

  it('unmatches a matched line and runs auto-match again', async () => {
    const user = userEvent.setup()
    vi.mocked(bankReconciliationApi.autoMatch).mockResolvedValue({ ...statement, autoMatchedLines: 1, autoSuggestedLines: 0 })
    renderWithProviders(<BankReconciliationPage />)

    await screen.findAllByTestId('statement-line-card')
    await user.click(within(cardFor('UPI SETTLEMENT')).getByRole('button', { name: /^Unmatch line of/ }))
    expect(bankReconciliationApi.updateLine).toHaveBeenCalledWith(3, 4, { status: 'UNMATCHED' })

    await user.click(screen.getByRole('button', { name: 'Run auto-match' }))
    expect(bankReconciliationApi.autoMatch).toHaveBeenCalledWith(3, 12)
    expect(await screen.findByRole('status')).toHaveTextContent('Auto-match finished1 matched · 0 suggested')
  })
})
