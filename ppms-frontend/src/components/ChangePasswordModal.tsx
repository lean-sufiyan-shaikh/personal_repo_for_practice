import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { authApi } from '../api/authApi'
import { useAuthStore } from '../store/authStore'
import { PasswordInput } from './PasswordInput'
import { Modal } from './Modal'
import { parseApiError } from '../utils/apiError'
import { PASSWORD_POLICY_REGEX } from '../constants/passwordPolicy'
import { FormField } from './FormField'

interface ChangePasswordModalProps {
  onClose: () => void
  successMessage?: string
  /** The password was set by someone else: the dialog cannot be dismissed by a stray click, and leaving it signs out. */
  required?: boolean
}

export function ChangePasswordModal({ onClose, successMessage, required = false }: ChangePasswordModalProps) {
  const { t } = useTranslation(['auth', 'common'])
  const navigate = useNavigate()
  const { clearAuth } = useAuthStore()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)

  const changePasswordMutation = useMutation({
    mutationFn: ({ currentPassword, newPassword }: { currentPassword: string; newPassword: string }) =>
      authApi.changePassword(currentPassword, newPassword),
    onSuccess: (data) => {
      clearAuth()
      navigate('/login', { state: { message: successMessage ?? data.message } })
    },
    onError: (err) => {
      setPasswordError(parseApiError(err, t('changePassword.failed')))
    },
  })

  const handleChangePassword = () => {
    setPasswordError(null)

    if (!currentPassword || !newPassword || !confirmPassword) {
      setPasswordError(t('changePassword.allRequired'))
      return
    }

    if (!PASSWORD_POLICY_REGEX.test(newPassword)) {
      setPasswordError(t('passwordPolicy'))
      return
    }

    if (newPassword !== confirmPassword) {
      setPasswordError(t('changePassword.mismatch'))
      return
    }

    changePasswordMutation.mutate({ currentPassword, newPassword })
  }

  return (
    <Modal
      title={required ? t('changePassword.requiredTitle') : t('changePassword.title')}
      subtitle={required ? t('changePassword.requiredSubtitle') : t('changePassword.subtitle')}
      tone="warning"
      size="lg"
      onClose={onClose}
      closeOnBackdropClick={!required}
      bodyClassName="space-y-4"
      footer={
        <>
          <button
            onClick={onClose}
            disabled={changePasswordMutation.isPending}
            className="ui-btn ui-btn-secondary"
          >
            {required ? t('changePassword.signOut') : t('common:actions.cancel')}
          </button>
          <button
            onClick={handleChangePassword}
            disabled={changePasswordMutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {changePasswordMutation.isPending ? t('changePassword.submitting') : t('changePassword.submit')}
          </button>
        </>
      }
    >
      <div>
        <FormField label={t('fields.currentPassword')}>
          <PasswordInput
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            autoComplete="current-password"
            className="shadow-sm"
          />
        </FormField>
      </div>

      <div>
        <FormField label={t('fields.newPassword')}>
          <PasswordInput
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            autoComplete="new-password"
            className="shadow-sm"
          />
        </FormField>
        <p className="ui-help">{t('passwordPolicy')}</p>
      </div>

      <div>
        <FormField label={t('fields.confirmNewPassword')}>
          <PasswordInput
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleChangePassword() }}
            autoComplete="new-password"
            className="shadow-sm"
          />
        </FormField>
      </div>

      {passwordError && (
        <div className="ui-alert ui-alert-danger" role="alert">
          <p className="text-sm text-bad">{passwordError}</p>
        </div>
      )}
    </Modal>
  )
}
