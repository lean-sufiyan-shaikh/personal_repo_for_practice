import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { CreditClient } from '../../api/pumpApi'
import { ClientStatusToggle } from './ClientStatusToggle'

function client(isActive: boolean) {
  return { id: 4, name: 'Patil Transport', isActive } as CreditClient
}

describe('ClientStatusToggle', () => {
  it('asks before disabling an active client', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ClientStatusToggle client={client(true)} onChange={onChange} disabled={false} />)

    await user.click(screen.getByRole('button', { name: 'Disable client Patil Transport' }))
    expect(screen.getByText('Disable this client?')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Yes' }))
    expect(onChange).toHaveBeenCalledWith(false)
  })

  it('enables a disabled client in one click', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ClientStatusToggle client={client(false)} onChange={onChange} disabled={false} />)

    await user.click(screen.getByRole('button', { name: 'Enable' }))
    expect(onChange).toHaveBeenCalledWith(true)
  })
})
