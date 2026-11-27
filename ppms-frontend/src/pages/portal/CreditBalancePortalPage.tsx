import { useState } from 'react'
import axios, { isAxiosError } from 'axios'
import { useTranslation } from 'react-i18next'
import { FormField } from '../../components/FormField'
import { PasswordInput } from '../../components/PasswordInput'
import { PORTAL_PIN_PATTERN, digitsOnly } from '../../constants/portalPin'
import { NumericInput } from '../../components/NumericInput'
import { LoadingButton } from '../../components/LoadingButton'
import { AuthCard, AuthScreen } from '../auth/AuthCard'
import { PortalBalanceResult } from './PortalBalanceResult'
import type { PortalBalance } from './PortalBalanceResult'

// Uses a plain axios instance — no auth header, no JWT
const publicClient = axios.create({ baseURL: '/api' })

const DEFAULT_WAIT_MINUTES = 15

/** A reminder links here with ?pump=<id>, so the client only types their phone and PIN. */
function pumpIdFromLink(): string {
  const pump = new URLSearchParams(window.location.search).get('pump') ?? ''
  return /^\d+$/.test(pump) ? pump : ''
}

type PortalError =
  | { kind: 'incomplete' | 'noMatch' | 'lookupFailed' }
  | { kind: 'tooManyAttempts'; minutes: number }

function lookupError(err: unknown): PortalError {
  const response = isAxiosError(err) ? err.response : undefined
  if (response?.status === 429) {
    const minutes = (response.data as { params?: { minutes?: unknown } } | undefined)?.params?.minutes
    return { kind: 'tooManyAttempts', minutes: typeof minutes === 'number' && minutes > 0 ? minutes : DEFAULT_WAIT_MINUTES }
  }
  if (response?.status === 404 || response?.status === 400) return { kind: 'noMatch' }
  return { kind: 'lookupFailed' }
}

export default function CreditBalancePortalPage() {
  const { t } = useTranslation('portal')
  const [pumpId, setPumpId]   = useState(pumpIdFromLink)
  const [phone, setPhone]     = useState('')
  const [pin, setPin]         = useState('')
  const [result, setResult]   = useState<PortalBalance | null>(null)
  const [error, setError]     = useState<PortalError | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!pumpId.trim() || !/^\d{10}$/.test(phone) || !PORTAL_PIN_PATTERN.test(pin)) {
      setError({ kind: 'incomplete' })
      return
    }
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const res = await publicClient.post<PortalBalance>('/public/credit-balance', {
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
    <AuthScreen brandMessage={{ tagline: t('brand.tagline'), supportingLine: t('brand.supportingLine') }}>
      <AuthCard
        title={t('title')}
        description={t('subtitle')}
        footer={<p className="text-xs text-ink-3">{t('footer')}</p>}
      >
        <form onSubmit={handleSubmit} className="space-y-3">
          <FormField label={t('form.pumpId')} help={t('form.pumpIdHelp')}>
            <NumericInput min="1" step="1" inputMode="numeric" value={pumpId}
              onChange={e => setPumpId(e.target.value)} required />
          </FormField>

          <FormField label={t('form.phone')} help={t('form.phoneHelp')}>
            <input
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              value={phone}
              onChange={e => setPhone(digitsOnly(e.target.value).slice(0, 10))}
              required
            />
          </FormField>

          <FormField label={t('form.pin')} help={t('form.pinHelp')}>
            <PasswordInput
              toggleLabel={t('form.showPin')}
              value={pin}
              onChange={e => setPin(digitsOnly(e.target.value))}
              inputMode="numeric"
              autoComplete="off"
              maxLength={6}
              required
            />
          </FormField>

          {error && (
            <p role="alert" className="ui-alert ui-alert-danger">
              {error.kind === 'tooManyAttempts' ? t('errors.tooManyAttempts', { minutes: error.minutes }) : t(`errors.${error.kind}`)}
            </p>
          )}

          <LoadingButton type="submit" isLoading={loading} loadingLabel={t('form.checking')} className="ui-btn-primary w-full">
            {t('form.check')}
          </LoadingButton>
        </form>

        {result && <PortalBalanceResult result={result} />}
      </AuthCard>
    </AuthScreen>
  )
}
