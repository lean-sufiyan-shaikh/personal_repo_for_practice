import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { authApi } from '../../api/authApi'

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const token = searchParams.get('token') ?? ''

  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!token) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center px-4">
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8 w-full max-w-sm text-center space-y-4">
          <span className="text-4xl">⚠️</span>
          <h1 className="text-lg font-bold text-slate-800">Invalid Reset Link</h1>
          <p className="text-sm text-slate-500">
            This password reset link is invalid or missing a token.
            Please request a new one from the forgot password page.
          </p>
          <Link to="/forgot-password" className="block text-sm text-blue-600 hover:underline">
            Request new link
          </Link>
        </div>
      </div>
    )
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (newPassword.length < 6) { setError('Password must be at least 6 characters'); return }
    if (newPassword !== confirmPassword) { setError('Passwords do not match'); return }
    setLoading(true); setError(null)
    try {
      await authApi.resetPassword(token, newPassword)
      navigate('/login', { state: { message: 'Password reset successful. Please log in with your new password.' } })
    } catch (err: any) {
      setError(err?.response?.data?.message ?? 'Failed to reset password. The link may have expired.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center px-4">
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8 w-full max-w-sm space-y-6">

        <div>
          <h1 className="text-xl font-bold text-slate-800">Set New Password</h1>
          <p className="text-sm text-slate-500 mt-1">Enter and confirm your new password below.</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs text-slate-500 mb-1">New Password</label>
            <input
              type="password"
              value={newPassword}
              onChange={e => setNewPassword(e.target.value)}
              placeholder="At least 6 characters"
              className="w-full border border-slate-200 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
              autoFocus
              minLength={6}
              required
            />
          </div>

          <div>
            <label className="block text-xs text-slate-500 mb-1">Confirm Password</label>
            <input
              type="password"
              value={confirmPassword}
              onChange={e => setConfirmPassword(e.target.value)}
              placeholder="Repeat the password"
              className="w-full border border-slate-200 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-400"
              required
            />
          </div>

          {error && <p className="text-xs text-red-600">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-600 text-white text-sm font-medium py-2.5 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {loading ? 'Resetting…' : 'Reset Password'}
          </button>
        </form>

        <Link to="/login" className="block text-center text-sm text-slate-400 hover:text-slate-600">
          Back to login
        </Link>
      </div>
    </div>
  )
}
