import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { userApi } from '../api/userApi'
import { PasswordInput } from './PasswordInput'
import { Modal } from './Modal'
import { FormField } from './FormField'
import { parseApiError } from '../utils/apiError'
import { PASSWORD_POLICY_MESSAGE, PASSWORD_POLICY_REGEX } from '../constants/passwordPolicy'

interface ResetPasswordDialogProps {
  userId: number
  userName: string
  onClose: () => void
}

/** Sets a temporary password for someone else; they are signed out everywhere and must choose their own at next sign-in. */
export function ResetPasswordDialog({ userId, userName, onClose }: ResetPasswordDialogProps) {
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const resetMutation = useMutation({
    mutationFn: () => userApi.resetPassword(userId, temporaryPassword),
    onError: (err) => setError(parseApiError(err, 'Failed to reset password.')),
  })

  const submit = () => {
    setError(null)
    if (!temporaryPassword || !confirmPassword) {
      setError('Both password fields are required.')
      return
    }
    if (!PASSWORD_POLICY_REGEX.test(temporaryPassword)) {
      setError(PASSWORD_POLICY_MESSAGE)
      return
    }
    if (temporaryPassword !== confirmPassword) {
      setError('Temporary password and confirm password must match.')
      return
    }
    resetMutation.mutate()
  }

  return (
    <Modal
      title="Reset Password"
      subtitle={`Set a temporary password for ${userName}.`}
      tone="warning"
      size="lg"
      onClose={onClose}
      canClose={!resetMutation.isPending}
      closeOnBackdropClick
      bodyClassName={resetMutation.isSuccess ? 'space-y-3' : 'space-y-4'}
      footer={resetMutation.isSuccess ? (
        <button type="button" onClick={onClose} className="ui-btn ui-btn-primary">Done</button>
      ) : (
        <>
          <button type="button" onClick={onClose} disabled={resetMutation.isPending} className="ui-btn ui-btn-secondary">
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={resetMutation.isPending} className="ui-btn ui-btn-primary">
            {resetMutation.isPending ? 'Resetting...' : 'Reset Password'}
          </button>
        </>
      )}
    >
      {resetMutation.isSuccess ? (
        <div className="ui-alert ui-alert-success" role="status">
          <p className="text-sm">
            Password reset. {userName} has been signed out everywhere and must choose a new password the next
            time they sign in. Share the temporary password with them in person.
          </p>
        </div>
      ) : (
        <>
          <div>
            <FormField label="Temporary Password">
              <PasswordInput
                value={temporaryPassword}
                onChange={(e) => setTemporaryPassword(e.target.value)}
                autoComplete="new-password"
                className="shadow-sm"
              />
            </FormField>
            <p className="ui-help">{PASSWORD_POLICY_MESSAGE}</p>
          </div>
          <div>
            <FormField label="Confirm Temporary Password">
              <PasswordInput
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') submit() }}
                autoComplete="new-password"
                className="shadow-sm"
              />
            </FormField>
          </div>
          {error && (
            <div className="ui-alert ui-alert-danger" role="alert">
              <p className="text-sm text-bad">{error}</p>
            </div>
          )}
        </>
      )}
    </Modal>
  )
}
