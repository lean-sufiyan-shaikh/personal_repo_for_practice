import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { userApi } from '../api/userApi'
import { PasswordInput } from './PasswordInput'
import { Modal } from './Modal'
import { FormField } from './FormField'
import { parseApiError } from '../utils/apiError'
import { PASSWORD_POLICY_REGEX } from '../constants/passwordPolicy'

interface ResetPasswordDialogProps {
  userId: number
  userName: string
  onClose: () => void
}

/** Sets a temporary password for someone else; they are signed out everywhere and must choose their own at next sign-in. */
export function ResetPasswordDialog({ userId, userName, onClose }: ResetPasswordDialogProps) {
  const { t } = useTranslation(['auth', 'common'])
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const resetMutation = useMutation({
    mutationFn: () => userApi.resetPassword(userId, temporaryPassword),
    onError: (err) => setError(parseApiError(err, t('resetPasswordDialog.failed'))),
  })

  const submit = () => {
    setError(null)
    if (!temporaryPassword || !confirmPassword) {
      setError(t('resetPasswordDialog.bothRequired'))
      return
    }
    if (!PASSWORD_POLICY_REGEX.test(temporaryPassword)) {
      setError(t('passwordPolicy'))
      return
    }
    if (temporaryPassword !== confirmPassword) {
      setError(t('resetPasswordDialog.mismatch'))
      return
    }
    resetMutation.mutate()
  }

  return (
    <Modal
      title={t('resetPasswordDialog.title')}
      subtitle={t('resetPasswordDialog.subtitle', { name: userName })}
      tone="warning"
      size="lg"
      onClose={onClose}
      canClose={!resetMutation.isPending}
      closeOnBackdropClick
      bodyClassName={resetMutation.isSuccess ? 'space-y-3' : 'space-y-4'}
      footer={resetMutation.isSuccess ? (
        <button type="button" onClick={onClose} className="ui-btn ui-btn-primary">{t('resetPasswordDialog.done')}</button>
      ) : (
        <>
          <button type="button" onClick={onClose} disabled={resetMutation.isPending} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
          <button type="button" onClick={submit} disabled={resetMutation.isPending} className="ui-btn ui-btn-primary">
            {resetMutation.isPending ? t('resetPasswordDialog.submitting') : t('resetPasswordDialog.submit')}
          </button>
        </>
      )}
    >
      {resetMutation.isSuccess ? (
        <div className="ui-alert ui-alert-success" role="status">
          <p className="text-sm">{t('resetPasswordDialog.success', { name: userName })}</p>
        </div>
      ) : (
        <>
          <div>
            <FormField label={t('fields.temporaryPassword')}>
              <PasswordInput
                value={temporaryPassword}
                onChange={(e) => setTemporaryPassword(e.target.value)}
                autoComplete="new-password"
                className="shadow-sm"
              />
            </FormField>
            <p className="ui-help">{t('passwordPolicy')}</p>
          </div>
          <div>
            <FormField label={t('fields.confirmTemporaryPassword')}>
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
