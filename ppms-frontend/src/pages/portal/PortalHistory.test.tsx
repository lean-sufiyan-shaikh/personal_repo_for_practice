import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { portalApi } from './portalClient'
import { PortalHistory } from './PortalHistory'

vi.mock('./portalClient', () => ({
  portalApi: { getHistory: vi.fn(), getStatementHtml: vi.fn() },
}))

function failedWith(status: number) {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status, statusText: '', headers: {}, config: { headers: new AxiosHeaders() }, data: {},
  })
}

describe('PortalHistory', () => {
  beforeEach(() => {
    vi.mocked(portalApi.getHistory).mockReset()
    vi.mocked(portalApi.getStatementHtml).mockReset()
  })

  it('lists the recent transactions of the session with payments as credits and disputed rows marked', async () => {
    const user = userEvent.setup()
    vi.mocked(portalApi.getHistory).mockResolvedValue([
      { date: '2026-11-20', type: 'SALE', accountName: 'Truck 7', detail: 'DIESEL', reference: null, amount: 1200,
        countedInBalance: true, disputed: true },
      { date: '2026-11-21', type: 'PAYMENT', accountName: null, detail: 'UPI', reference: null, amount: 500,
        countedInBalance: true, disputed: false },
    ])
    render(<PortalHistory sessionToken="token-1" />)

    await user.click(screen.getByRole('button', { name: 'Recent transactions' }))

    expect(portalApi.getHistory).toHaveBeenCalledWith('token-1')
    expect(await screen.findByText('Disputed')).toBeInTheDocument()
    expect(screen.getByText('Fuel')).toBeInTheDocument()
    expect(screen.getByText('−₹500.00')).toHaveClass('text-ok')
  })

  it('asks to check the balance again when the session has ended, and says when downloads are used up', async () => {
    const user = userEvent.setup()
    vi.mocked(portalApi.getHistory).mockRejectedValue(failedWith(400))
    vi.mocked(portalApi.getStatementHtml).mockRejectedValue(failedWith(429))
    render(<PortalHistory sessionToken="token-1" />)

    await user.click(screen.getByRole('button', { name: 'Recent transactions' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Your session has ended.')

    await user.click(screen.getByRole('button', { name: "Last month's statement" }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many statements downloaded.')
    expect(portalApi.getStatementHtml).toHaveBeenCalledWith('token-1', 'PREVIOUS')
  })
})
