import { useState } from 'react'
import axios, { isAxiosError } from 'axios'
import { Fuel } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { FormField } from '../../components/FormField'
import { PasswordInput } from '../../components/PasswordInput'
import { PORTAL_PIN_PATTERN, digitsOnly } from '../../constants/portalPin'
import { MoneyText } from '../../components/AmountText'
import { LanguagePicker } from '../../components/LanguagePicker'
import { activeLanguage, changeAppLanguage } from '../../i18n'
import { storeLanguage } from '../../i18n/detectLanguage'
import type { AppLanguage } from '../../i18n/languages'

// Uses a plain axios instance — no auth header, no JWT
const publicClient = axios.create({ baseURL: '/api' })

interface BalanceResult {
  outstandingAmount: number
  creditLimit: number
}

type PortalError = 'incomplete' | 'noMatch' | 'tooManyAttempts' | 'lookupFailed'

function lookupError(err: unknown): PortalError {
  const status = isAxiosError(err) ? err.response?.status : undefined
  if (status === 429) return 'tooManyAttempts'
  if (status === 404 || status === 400) return 'noMatch'
  return 'lookupFailed'
}

function chooseLanguage(language: AppLanguage) {
  storeLanguage(language)
  changeAppLanguage(language).catch((error: unknown) => console.error('Could not switch the screen language', error))
}

export default function CreditBalancePortalPage() {
  const { t } = useTranslation('portal')
  const [pumpId, setPumpId]   = useState('')
  const [phone, setPhone]     = useState('')
  const [pin, setPin]         = useState('')
  const [result, setResult]   = useState<BalanceResult | null>(null)
  const [error, setError]     = useState<PortalError | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!pumpId.trim() || !/^\d{10}$/.test(phone) || !PORTAL_PIN_PATTERN.test(pin)) {
      setError('incomplete')
      return
    }
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const res = await publicClient.post<BalanceResult>('/public/credit-balance', {
        pumpId: parseInt(pumpId), phone, pin,
      })
      setResult(res.data)
    } catch (err) {
      setError(lookupError(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center px-4">

      <div className="ui-card w-full max-w-sm p-0 overflow-hidden shadow-raised">

        {/* Header */}
        <div className="bg-ink border-t-4 border-accent px-6 py-5 text-surface text-center">
          <div className="flex justify-center mb-2"><Fuel size={28} strokeWidth={1.8} className="text-accent" /></div>
          <h1 className="text-lg font-bold">{t('title')}</h1>
          <p className="text-on-dark-2 text-xs mt-1">{t('subtitle')}</p>
        </div>

        {/* Form */}
        <div className="px-6 py-5">
          <form onSubmit={handleSubmit} className="space-y-3">
            <div>
              <label className="block">
                <span className="ui-label">{t('form.pumpId')}</span>
                <input
                  type="number"
                  min="1"
                  value={pumpId}
                  onChange={e => setPumpId(e.target.value)}
                  className="text-sm"
                  placeholder={t('form.pumpIdPlaceholder')}
                  required
                />
              </label>
              <p className="text-xs text-ink-3 mt-0.5">{t('form.pumpIdHelp')}</p>
            </div>

            <div>
              <label className="block">
                <span className="ui-label">{t('form.phone')}</span>
                <input
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  value={phone}
                  onChange={e => setPhone(digitsOnly(e.target.value).slice(0, 10))}
                  className="text-sm"
                  placeholder={t('form.phonePlaceholder')}
                  required
                />
              </label>
            </div>

            <FormField label={t('form.pin')} help={t('form.pinHelp')}>
              <PasswordInput
                toggleLabel={t('form.showPin')}
                value={pin}
                onChange={e => setPin(digitsOnly(e.target.value))}
                inputMode="numeric"
                autoComplete="off"
                maxLength={6}
                className="text-sm"
                placeholder={t('form.pinPlaceholder')}
                required
              />
            </FormField>

            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{t(`errors.${error}`)}</div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="ui-btn ui-btn-primary w-full disabled:opacity-50"
            >
              {loading ? t('form.checking') : t('form.check')}
            </button>
          </form>

          {/* Result card */}
          {result && (
            <div className="mt-4 ui-card-plain ui-card-muted px-4 py-4">
              <p className="text-xs text-ink-3 mb-3">{t('result.account')}</p>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-ink-3">{t('result.outstanding')}</span>
                  <MoneyText value={result.outstandingAmount} tone={result.outstandingAmount > 0 ? 'bad' : 'ok'} className="text-sm font-bold" />
                </div>
                {result.creditLimit > 0 && (
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-ink-3">{t('result.creditLimit')}</span>
                    <MoneyText value={result.creditLimit} className="text-sm text-ink" />
                  </div>
                )}
              </div>

              {result.outstandingAmount <= 0 && (
                <p className="text-xs text-ok font-medium mt-2">{t('result.settled')}</p>
              )}
            </div>
          )}

          <LanguagePicker value={activeLanguage()} onChange={chooseLanguage} className="mt-4 max-w-48" />
        </div>

        {/* Footer */}
        <div className="px-6 py-3 bg-bg border-t border-border text-center">
          <p className="text-xs text-ink-3">
            {t('footer')}
          </p>
        </div>
      </div>
    </div>
  )
}
