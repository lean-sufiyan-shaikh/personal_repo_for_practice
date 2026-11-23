import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Check } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { authApi } from '../../api/authApi'
import { parseApiError } from '../../utils/apiError'

function NoEmailHelp() {
  const { t } = useTranslation('auth')
  return (
    <div className="ui-alert ui-alert-warning text-sm">
      <p className="font-medium">{t('forgotPassword.noEmailTitle')}</p>
      <p className="mt-1">{t('forgotPassword.noEmailBody')}</p>
    </div>
  )
}

export default function ForgotPasswordPage() {
  const { t } = useTranslation('auth')
  const [phoneNumber, setPhoneNumber] = useState('')
  const [loading, setLoading] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!phoneNumber.trim()) { setError(t('forgotPassword.phoneRequired')); return }
    setLoading(true); setError(null)
    try {
      await authApi.forgotPassword(phoneNumber.trim())
      setSent(true)
    } catch (err) {
      setError(parseApiError(err))
    } finally {
      setLoading(false)
    }
  }

  if (sent) {
    return (
      <div className="ui-auth-shell">
        <div className="ui-auth-card text-center space-y-4">
          <div className="w-14 h-14 bg-ok-bg rounded-full flex items-center justify-center mx-auto">
            <Check size={28} strokeWidth={2.5} className="text-ok" />
          </div>
          <h1 className="ui-title-sm">{t('forgotPassword.sentTitle')}</h1>
          <p className="ui-subtitle">{t('forgotPassword.sentBody')}</p>
          <p className="text-xs text-ink-3">{t('forgotPassword.linkExpiry')}</p>
          <NoEmailHelp />
          <Link to="/login" className="ui-link inline-flex justify-center">
            {t('backToLogin')}
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="ui-auth-shell">
      <div className="ui-auth-card space-y-6">

        <div>
          <h1 className="ui-title-sm">{t('forgotPassword.title')}</h1>
          <p className="ui-subtitle">{t('forgotPassword.subtitle')}</p>
        </div>

        <NoEmailHelp />

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="phoneNumber" className="ui-label">{t('fields.phoneNumber')}</label>
            <input
              id="phoneNumber"
              type="tel"
              inputMode="numeric"
              autoComplete="tel"
              value={phoneNumber}
              onChange={e => setPhoneNumber(e.target.value)}
              placeholder={t('forgotPassword.phonePlaceholder')}
              className="shadow-raised"
            />
          </div>

          {error && <p className="ui-error-text" role="alert">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="ui-btn ui-btn-primary w-full"
          >
            {loading ? t('forgotPassword.sending') : t('forgotPassword.send')}
          </button>
        </form>

        <Link to="/login" className="ui-link block text-center">
          {t('backToLogin')}
        </Link>
      </div>
    </div>
  )
}
