import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { AlertTriangle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { authApi } from '../../api/authApi'
import { PasswordInput } from '../../components/PasswordInput'
import { parseApiError } from '../../utils/apiError'
import { PASSWORD_POLICY_REGEX } from '../../constants/passwordPolicy'

export default function ResetPasswordPage() {
  const { t } = useTranslation('auth')
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const token = searchParams.get('token') ?? ''

  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!token) {
    return (
      <div className="ui-auth-shell">
        <div className="ui-auth-card text-center space-y-4">
          <div className="flex justify-center">
            <div className="w-14 h-14 bg-warn-bg rounded-card flex items-center justify-center">
              <AlertTriangle size={28} strokeWidth={2} className="text-warn" />
            </div>
          </div>
          <h1 className="ui-title-sm">{t('resetPassword.invalidLinkTitle')}</h1>
          <p className="ui-subtitle">{t('resetPassword.invalidLinkBody')}</p>
          <Link to="/forgot-password" className="ui-link inline-flex justify-center">
            {t('resetPassword.requestNewLink')}
          </Link>
        </div>
      </div>
    )
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!PASSWORD_POLICY_REGEX.test(newPassword)) { setError(t('passwordPolicy')); return }
    if (newPassword !== confirmPassword) { setError(t('resetPassword.mismatch')); return }
    setLoading(true); setError(null)
    try {
      await authApi.resetPassword(token, newPassword)
      navigate('/login', { state: { message: t('resetPassword.success') } })
    } catch (err) {
      setError(parseApiError(err, t('resetPassword.failed')))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="ui-auth-shell">
      <div className="ui-auth-card space-y-6">

        <div>
          <h1 className="ui-title-sm">{t('resetPassword.title')}</h1>
          <p className="ui-subtitle">{t('resetPassword.subtitle')}</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="newPassword" className="ui-label">{t('fields.newPassword')}</label>
            <PasswordInput
              id="newPassword"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              autoComplete="new-password"
              placeholder={t('resetPassword.newPasswordPlaceholder')}
              className="shadow-raised"
              minLength={8}
              required
            />
            <p className="ui-help">{t('passwordPolicy')}</p>
          </div>

          <div>
            <label htmlFor="confirmPassword" className="ui-label">{t('fields.confirmPassword')}</label>
            <PasswordInput
              id="confirmPassword"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              autoComplete="new-password"
              placeholder={t('resetPassword.confirmPlaceholder')}
              className="shadow-raised"
              required
            />
          </div>

          {error && <p className="ui-error-text" role="alert">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="ui-btn ui-btn-primary w-full"
          >
            {loading ? t('resetPassword.submitting') : t('resetPassword.submit')}
          </button>
        </form>

        <Link to="/login" className="ui-link block text-center">
          {t('backToLogin')}
        </Link>
      </div>
    </div>
  )
}
