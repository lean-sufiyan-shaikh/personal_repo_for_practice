import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CreditClient } from '../../../api/creditApi'
import { creditDeskApi } from '../../../api/creditDeskApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import { ClientDeskMenu } from './ClientDeskMenu'

vi.mock('../../../api/creditDeskApi', () => ({
  creditDeskApi: {
    writeOff: vi.fn(), closeAccount: vi.fn(), reopenAccount: vi.fn(), mergeInto: vi.fn(), refundAdvance: vi.fn(),
    issueCreditNote: vi.fn(), receiveDeposit: vi.fn(), applyDeposit: vi.fn(), refundDeposit: vi.fn(),
  },
}))

const PUMP_ID = 3
const client: CreditClient = {
  id: 40, pumpId: PUMP_ID, name: 'Acme Transport', phone: '9876543210', notes: null,
  creditLimit: 50_000, unlimited: false, outstandingBalance: 12_000, outstandingInterest: 0, totalInterestRecovered: 0,
  monthlyInterestRate: 0, interestGraceDays: 1, createdAt: '2026-01-01T00:00:00Z',
  parentClientId: null, parentClientName: null, isParent: false, advance: 0, depositHeld: 500,
}
const duplicate: CreditClient = { ...client, id: 41, name: 'Acme Transport (old)' }

async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'More account actions' }))
  return screen.getByRole('menu')
}

describe('ClientDeskMenu', () => {
  beforeEach(() => {
    vi.mocked(creditDeskApi.writeOff).mockReset().mockResolvedValue({} as never)
    vi.mocked(creditDeskApi.mergeInto).mockReset().mockResolvedValue({} as never)
  })

  it('gives the owner write-off, merge and close, and an admin none of them', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(<ClientDeskMenu pumpId={PUMP_ID} client={client} allClients={[client]} userRole="OWNER" />)
    const ownerMenu = await openMenu(user)
    expect(within(ownerMenu).getByRole('menuitem', { name: /Write off bad debt/ })).toBeInTheDocument()
    expect(within(ownerMenu).getByRole('menuitem', { name: /Close account/ })).toBeInTheDocument()
    expect(within(ownerMenu).getByRole('menuitem', { name: /Apply deposit to dues/ })).toBeInTheDocument()
    unmount()

    renderWithProviders(<ClientDeskMenu pumpId={PUMP_ID} client={client} allClients={[client]} userRole="ADMIN" />)
    const adminMenu = await openMenu(user)
    expect(within(adminMenu).queryByRole('menuitem', { name: /Write off bad debt/ })).not.toBeInTheDocument()
    expect(within(adminMenu).queryByRole('menuitem', { name: /Merge into another client/ })).not.toBeInTheDocument()
    expect(within(adminMenu).getByRole('menuitem', { name: /Credit note/ })).toBeInTheDocument()
  })

  it('writes off everything owed when the amount is left empty, once a reason is given', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ClientDeskMenu pumpId={PUMP_ID} client={client} allClients={[client]} userRole="OWNER" />)

    await user.click(within(await openMenu(user)).getByRole('menuitem', { name: /Write off bad debt/ }))
    const dialog = screen.getByRole('dialog', { name: /Write off bad debt/ })
    await user.click(within(dialog).getByRole('button', { name: 'Write off' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Give a reason.')

    await user.type(within(dialog).getByLabelText('Reason'), 'Client vanished')
    await user.click(within(dialog).getByRole('button', { name: 'Write off' }))

    expect(creditDeskApi.writeOff).toHaveBeenCalledWith(PUMP_ID, client.id,
      { amount: undefined, reason: 'Client vanished' }, expect.any(String))
  })

  it('offers only reopening on a closed account, and merges a duplicate into a client on the same level', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(<ClientDeskMenu pumpId={PUMP_ID} allClients={[client]} userRole="OWNER"
      client={{ ...client, closedAt: '2026-11-01T00:00:00Z', closeReason: 'Stopped', depositHeld: 0 }} />)
    expect(within(await openMenu(user)).getAllByRole('menuitem').map(item => item.textContent))
      .toEqual([expect.stringContaining('Reopen account')])
    unmount()

    renderWithProviders(<ClientDeskMenu pumpId={PUMP_ID} client={duplicate} allClients={[client, duplicate]} userRole="OWNER" />)
    await user.click(within(await openMenu(user)).getByRole('menuitem', { name: /Merge into another client/ }))
    const dialog = screen.getByRole('dialog', { name: /Merge into another client/ })
    await user.click(within(dialog).getByRole('button', { name: /Merge into/ }))
    await user.click(screen.getByRole('option', { name: 'Acme Transport' }))
    await user.click(within(dialog).getByRole('button', { name: 'Merge' }))

    expect(creditDeskApi.mergeInto).toHaveBeenCalledWith(PUMP_ID, duplicate.id, client.id)
  })
})

describe('ClientDeskMenu on a phone', () => {
  resetScreenWidthAfterEach()

  it('keeps the menu within the screen', async () => {
    setScreenWidth(375)
    const user = userEvent.setup()
    renderWithProviders(<ClientDeskMenu pumpId={PUMP_ID} client={client} allClients={[client]} userRole="OWNER" />)

    expect(await openMenu(user)).toHaveClass('max-w-[calc(100vw-2rem)]')
  })
})
