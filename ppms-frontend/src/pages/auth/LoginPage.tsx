import { useState } from 'react'
import { Link, useNavigate, useSearchParams, useLocation } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { authApi } from '../../api/authApi'
import { useAuthStore } from '../../store/authStore'
import type { AuthUser } from '../../types/auth'

const loginSchema = z.object({
  phoneNumber: z.string().length(10, 'Phone number must be exactly 10 digits').regex(/^\d+$/, 'Must be digits only'),
  password: z.string().min(1, 'Password is required'),
})

type LoginFormData = z.infer<typeof loginSchema>

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const sessionExpired = searchParams.get('reason') === 'session_expired'
  const successMessage = (location.state as { message?: string } | null)?.message ?? null
  const setAuth = useAuthStore((s) => s.setAuth)
  const [serverError, setServerError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const { register, handleSubmit, formState: { errors } } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
  })

  const onSubmit = async (data: LoginFormData) => {
    setServerError(null)
    setLoading(true)
    try {
      const response = await authApi.login(data)
      const user: AuthUser = {
        userId: response.userId,
        fullName: response.fullName,
        phoneNumber: response.phoneNumber,
        role: response.role,
        assignedPumpId: response.assignedPumpId,
      }
      setAuth(user, response.token)
      navigate(response.role === 'SUPER_ADMIN' ? '/super-admin' : '/dashboard')
    } catch (err: unknown) {
      const error = err as { response?: { data?: { message?: string } } }
      setServerError(error?.response?.data?.message ?? 'Login failed. Please try again.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-100">
      <div className="w-full max-w-sm bg-white rounded-2xl shadow-md p-8">

        <div className="mb-8 text-center">
          <h1 className="text-2xl font-bold text-slate-800">PPMS</h1>
          <p className="text-slate-500 text-sm mt-1">Petrol Pump Management System</p>
        </div>

        {sessionExpired && (
          <div className="mb-5 bg-amber-50 border border-amber-300 rounded-lg px-4 py-3 flex items-start gap-2.5">
            <span className="text-amber-500 text-base mt-0.5">⚠</span>
            <div>
              <p className="text-sm font-medium text-amber-800">Session expired</p>
              <p className="text-xs text-amber-600 mt-0.5">You were signed out because your session timed out. Please sign in again.</p>
            </div>
          </div>
        )}

        {successMessage && (
          <div className="mb-5 bg-emerald-50 border border-emerald-300 rounded-lg px-4 py-3">
            <p className="text-sm text-emerald-700">{successMessage}</p>
          </div>
        )}

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Phone Number
            </label>
            <input
              {...register('phoneNumber')}
              type="tel"
              inputMode="numeric"
              maxLength={10}
              placeholder="e.g. 9999999999"
              className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.phoneNumber && (
              <p className="text-red-500 text-xs mt-1">{errors.phoneNumber.message}</p>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Password
            </label>
            <input
              {...register('password')}
              type="password"
              placeholder="Enter your password"
              className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.password && (
              <p className="text-red-500 text-xs mt-1">{errors.password.message}</p>
            )}
          </div>

          {serverError && (
            <div className="bg-red-50 border border-red-200 rounded-lg p-3">
              <p className="text-red-600 text-sm">{serverError}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white font-medium py-2 rounded-lg text-sm transition-colors"
          >
            {loading ? 'Signing in...' : 'Sign In'}
          </button>

          <div className="text-center">
            <Link to="/forgot-password" className="text-xs text-slate-400 hover:text-blue-600 hover:underline">
              Forgot password?
            </Link>
          </div>

        </form>
      </div>
    </div>
  )
}
