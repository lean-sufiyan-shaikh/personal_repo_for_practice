import { useState } from 'react'
import { Link } from 'react-router-dom'
import { authApi } from '../../api/authApi'

export default function ForgotPasswordPage() {
  const [phoneNumber, setPhoneNumber] = useState('')
  const [loading, setLoading] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!phoneNumber.trim()) { setError('Phone number is required'); return }
    setLoading(true); setError(null)
    try {
      await authApi.forgotPassword(phoneNumber.trim())
      setSent(true)
    } catch (err: any) {
      setError(err?.response?.data?.message ?? 'Something went wrong. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  if (sent) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center px-4">
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8 w-full max-w-sm text-center space-y-4">
          <div className="w-14 h-14 bg-emerald-100 rounded-full flex items-center justify-center mx-auto">
            <span className="text-emerald-600 text-2xl">✓</span>
          </div>
          <h1 className="text-lg font-bold text-slate-800">Check your email</h1>
          <p className="text-sm text-slate-500">
            If this phone number is registered and has an email address on file,
            we have sent a password reset link. Check your inbox.
          </p>
          <p className="text-xs text-slate-400">The link expires in 1 hour.</p>
          <Link to="/login" className="block text-sm text-blue-600 hover:underline">
            Back to login
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center px-4">
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8 w-full max-w-sm space-y-6">

        <div>
          <h1 className="text-xl font-bold text-slate-800">Forgot Password</h1>
          <p className="text-sm text-slate-500 mt-1">
            Enter your registered phone number and we'll send a reset link to your email.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs text-slate-500 mb-1">Phone Number</label>
            <input
              type="tel"
              value={phoneNumber}
              onChange={e => setPhoneNumber(e.target.value)}
              placeholder="e.g. 9876543210"
              className="w-full border border-slate-200 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
              autoFocus
            />
          </div>

          {error && <p className="text-xs text-red-600">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-600 text-white text-sm font-medium py-2.5 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {loading ? 'Sending…' : 'Send Reset Link'}
          </button>
        </form>

        <Link to="/login" className="block text-center text-sm text-slate-400 hover:text-slate-600">
          Back to login
        </Link>
      </div>
    </div>
  )
}
