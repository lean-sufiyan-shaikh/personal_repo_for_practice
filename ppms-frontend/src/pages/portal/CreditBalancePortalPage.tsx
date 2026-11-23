import { useState } from 'react'
import axios, { isAxiosError } from 'axios'
import { Fuel } from 'lucide-react'
import { FormField } from '../../components/FormField'
import { PasswordInput } from '../../components/PasswordInput'
import { PORTAL_PIN_PATTERN, digitsOnly } from '../../constants/portalPin'
import { MoneyText } from '../../components/AmountText'

// Uses a plain axios instance — no auth header, no JWT
const publicClient = axios.create({ baseURL: '/api' })

interface BalanceResult {
  outstandingAmount: number
  creditLimit: number
}

const DETAILS_DO_NOT_MATCH = "Details don't match. Check your phone number and PIN, or ask the pump for a PIN."
const TOO_MANY_ATTEMPTS = 'Too many attempts. Please wait 15 minutes and try again.'
const LOOKUP_FAILED = 'Could not check your balance. Please try again.'

function lookupErrorMessage(err: unknown): string {
  const status = isAxiosError(err) ? err.response?.status : undefined
  if (status === 429) return TOO_MANY_ATTEMPTS
  if (status === 404 || status === 400) return DETAILS_DO_NOT_MATCH
  return LOOKUP_FAILED
}

export default function CreditBalancePortalPage() {
  const [pumpId, setPumpId]   = useState('')
  const [phone, setPhone]     = useState('')
  const [pin, setPin]         = useState('')
  const [result, setResult]   = useState<BalanceResult | null>(null)
  const [error, setError]     = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!pumpId.trim() || !/^\d{10}$/.test(phone) || !PORTAL_PIN_PATTERN.test(pin)) {
      setError('Enter the Pump ID, your 10-digit phone number and your 4 to 6 digit PIN.')
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
      setError(lookupErrorMessage(err))
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
          <h1 className="text-lg font-bold">Credit Balance Portal</h1>
          <p className="text-on-dark-2 text-xs mt-1">Check your outstanding fuel credit balance</p>
        </div>

        {/* Form */}
        <div className="px-6 py-5">
          <form onSubmit={handleSubmit} className="space-y-3">
            <div>
              <label className="block">
                <span className="ui-label">Pump ID</span>
                <input
                  type="number"
                  min="1"
                  value={pumpId}
                  onChange={e => setPumpId(e.target.value)}
                  className="text-sm"
                  placeholder="e.g. 1"
                  required
                />
              </label>
              <p className="text-xs text-ink-3 mt-0.5">Ask the pump manager for the Pump ID</p>
            </div>

            <div>
              <label className="block">
                <span className="ui-label">Registered Phone Number</span>
                <input
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  value={phone}
                  onChange={e => setPhone(digitsOnly(e.target.value).slice(0, 10))}
                  className="text-sm"
                  placeholder="10-digit mobile number"
                  required
                />
              </label>
            </div>

            <FormField label="Portal PIN" help="The pump gives you this PIN. Ask them if you don't have one.">
              <PasswordInput
                toggleLabel="Show PIN"
                value={pin}
                onChange={e => setPin(digitsOnly(e.target.value))}
                inputMode="numeric"
                autoComplete="off"
                maxLength={6}
                className="text-sm"
                placeholder="4 to 6 digits"
                required
              />
            </FormField>

            {error && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="ui-btn ui-btn-primary w-full disabled:opacity-50"
            >
              {loading ? 'Checking…' : 'Check Balance'}
            </button>
          </form>

          {/* Result card */}
          {result && (
            <div className="mt-4 ui-card-plain ui-card-muted px-4 py-4">
              <p className="text-xs text-ink-3 mb-3">Your credit account</p>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-ink-3">Outstanding Balance</span>
                  <MoneyText value={result.outstandingAmount} tone={result.outstandingAmount > 0 ? 'bad' : 'ok'} className="text-sm font-bold" />
                </div>
                {result.creditLimit > 0 && (
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-ink-3">Credit Limit</span>
                    <MoneyText value={result.creditLimit} className="text-sm text-ink" />
                  </div>
                )}
              </div>

              {result.outstandingAmount <= 0 && (
                <p className="text-xs text-ok font-medium mt-2">Your account is fully settled.</p>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-3 bg-bg border-t border-border text-center">
          <p className="text-xs text-ink-3">
            Powered by PPMS — Petrol Pump Management System
          </p>
        </div>
      </div>
    </div>
  )
}
