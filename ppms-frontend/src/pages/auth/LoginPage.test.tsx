import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { authApi } from '../../api/authApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { NO_CONNECTION_MESSAGE } from '../../utils/apiError'
import LoginPage from './LoginPage'

vi.mock('../../api/authApi', () => ({ authApi: { login: vi.fn() } }))

describe('LoginPage', () => {
  it('says the connection is down and lets the user try again', async () => {
    const user = userEvent.setup()
    vi.mocked(authApi.login).mockRejectedValue(new AxiosError('Network Error', 'ERR_NETWORK'))
    renderWithProviders(<LoginPage />)

    await user.type(screen.getByLabelText('Phone Number'), '9999999999')
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign In' }))

    expect(await screen.findByText(NO_CONNECTION_MESSAGE)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign In' })).toBeEnabled()
  })
})
