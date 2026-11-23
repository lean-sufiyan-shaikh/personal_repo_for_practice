import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { userApi } from '../api/userApi'
import { PasswordInput } from './PasswordInput'
import { ModalPortal } from './ModalPortal'
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

  const closeUnlessSaving = resetMutation.isPending ? undefined : onClose

  return (
    <ModalPortal onClose={closeUnlessSaving} closeOnBackdropClick>
    <div className="ui-modal-backdrop">
      <div className="ui-modal-panel">
        <div className="ui-modal-header ui-modal-header--themed ui-modal-header--warning">
          <div className="ui-modal-heading">
            <h2 className="ui-modal-title">Reset Password</h2>
            <p className="ui-modal-subtitle">Set a temporary password for {userName}.</p>
          </div>
          <button type="button" onClick={closeUnlessSaving} aria-label="Close" className="ui-btn ui-btn-ghost ui-modal-close">&times;</button>
        </div>

        {resetMutation.isSuccess ? (
          <>
            <div className="ui-modal-body space-y-3">
              <div className="ui-alert ui-alert-success" role="status">
                <p className="text-sm">
                  Password reset. {userName} has been signed out everywhere and must choose a new password the next
                  time they sign in. Share the temporary password with them in person.
                </p>
              </div>
            </div>
            <div className="ui-modal-footer">
              <button type="button" onClick={onClose} className="ui-btn ui-btn-primary">Done</button>
            </div>
          </>
        ) : (
          <>
            <div className="ui-modal-body space-y-4">
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
                  <p className="text-sm text-red-600">{error}</p>
                </div>
              )}
            </div>
            <div className="ui-modal-footer">
              <button type="button" onClick={onClose} disabled={resetMutation.isPending} className="ui-btn ui-btn-secondary">
                Cancel
              </button>
              <button type="button" onClick={submit} disabled={resetMutation.isPending} className="ui-btn ui-btn-primary">
                {resetMutation.isPending ? 'Resetting...' : 'Reset Password'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
    </ModalPortal>
  )
}
