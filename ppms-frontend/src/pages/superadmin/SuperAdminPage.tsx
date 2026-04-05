import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useAuthStore } from '../../store/authStore'
import { superAdminApi } from '../../api/superAdminApi'
import type { OnboardOwnerResponse, AddPumpRequest, UpdatePumpRequest, PumpSummary } from '../../api/superAdminApi'
import { Pagination } from '../../components/Pagination'
import client from '../../api/client'

// ── Validation schemas ────────────────────────────────────────────────────────

const addPumpSchema = z.object({
  pumpName:      z.string().min(1, 'Pump name is required'),
  pumpAddress:   z.string().min(1, 'Pump address is required'),
  maxNozzleCount: z.coerce.number().int().min(1, 'Must be at least 1').max(20, 'Cannot exceed 20'),
})
type AddPumpFormData = z.infer<typeof addPumpSchema>

const editPumpSchema = z.object({
  pumpName:    z.string().min(1, 'Pump name is required'),
  pumpAddress: z.string().min(1, 'Pump address is required'),
})
type EditPumpFormData = z.infer<typeof editPumpSchema>

const onboardSchema = z.object({
  fullName: z.string().min(1, 'Full name is required'),
  phoneNumber: z.string().length(10, 'Phone number must be exactly 10 digits').regex(/^\d+$/, 'Must be digits only'),
  password: z.string().min(6, 'Password must be at least 6 characters'),
  pumpName: z.string().min(1, 'Pump name is required'),
  pumpAddress: z.string().min(1, 'Pump address is required'),
  maxNozzleCount: z.coerce.number().int().min(1, 'Must be at least 1 nozzle').max(20, 'Cannot exceed 20 nozzles'),
})

type OnboardFormData = z.infer<typeof onboardSchema>

// ── Page component ────────────────────────────────────────────────────────────

export default function SuperAdminPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { user, clearAuth } = useAuthStore()

  const [createdOwner, setCreatedOwner] = useState<(OnboardOwnerResponse & { password: string }) | null>(null)
  const [showPassword, setShowPassword] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  // Which owner's "Add Pump" form is currently open (null = none)
  const [addPumpOwnerId, setAddPumpOwnerId] = useState<number | null>(null)
  const [addPumpError, setAddPumpError] = useState<string | null>(null)

  // Which pump's "Edit" form is currently open (null = none)
  const [editingPumpId, setEditingPumpId] = useState<number | null>(null)
  const [editPumpError, setEditPumpError] = useState<string | null>(null)

  // Owners list pagination
  const [ownersPage, setOwnersPage] = useState(0)
  const ownersPageSize = 10

  const { register, handleSubmit, reset, formState: { errors } } = useForm<OnboardFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(onboardSchema) as any,
  })

  // Fetch existing owners
  const { data: owners = [], isLoading: ownersLoading } = useQuery({
    queryKey: ['superadmin-owners'],
    queryFn: superAdminApi.listOwners,
  })

  // Platform analytics
  const { data: analytics } = useQuery({
    queryKey: ['superadmin-analytics'],
    queryFn: () => client.get('/super-admin/analytics').then(r => r.data),
  })

  // Onboard mutation
  const onboardMutation = useMutation({
    mutationFn: superAdminApi.onboardOwner,
    onSuccess: (data, variables) => {
      setCreatedOwner({ ...data, password: variables.password })
      setShowPassword(false)
      setServerError(null)
      reset()
      queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })
    },
    onError: (err: any) => {
      setServerError(err?.response?.data?.message ?? 'Failed to create owner. Please try again.')
    },
  })

  const onSubmit = (data: OnboardFormData) => {
    setServerError(null)
    setCreatedOwner(null)
    onboardMutation.mutate(data)
  }

  const {
    register: registerPump,
    handleSubmit: handleSubmitPump,
    reset: resetPump,
    formState: { errors: pumpErrors },
  } = useForm<AddPumpFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(addPumpSchema) as any,
  })

  const addPumpMutation = useMutation({
    mutationFn: ({ ownerId, data }: { ownerId: number; data: AddPumpRequest }) =>
      superAdminApi.addPumpToOwner(ownerId, data),
    onSuccess: () => {
      setAddPumpOwnerId(null)
      setAddPumpError(null)
      resetPump()
      queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })
      queryClient.invalidateQueries({ queryKey: ['superadmin-analytics'] })
    },
    onError: (err: any) =>
      setAddPumpError(err?.response?.data?.message ?? 'Failed to add pump. Please try again.'),
  })

  const {
    register: registerEdit,
    handleSubmit: handleSubmitEdit,
    reset: resetEdit,
    formState: { errors: editErrors },
  } = useForm<EditPumpFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(editPumpSchema) as any,
  })

  const editPumpMutation = useMutation({
    mutationFn: ({ pumpId, data }: { pumpId: number; data: UpdatePumpRequest }) =>
      superAdminApi.updatePump(pumpId, data),
    onSuccess: () => {
      setEditingPumpId(null)
      setEditPumpError(null)
      resetEdit()
      queryClient.invalidateQueries({ queryKey: ['superadmin-owners'] })
    },
    onError: (err: any) =>
      setEditPumpError(err?.response?.data?.message ?? 'Failed to update pump. Please try again.'),
  })

  const onAddPumpSubmit = (data: AddPumpFormData) => {
    if (!addPumpOwnerId) return
    setAddPumpError(null)
    addPumpMutation.mutate({ ownerId: addPumpOwnerId, data })
  }

  function toggleAddPump(ownerId: number) {
    if (addPumpOwnerId === ownerId) {
      setAddPumpOwnerId(null)
      setAddPumpError(null)
      resetPump()
    } else {
      setAddPumpOwnerId(ownerId)
      setAddPumpError(null)
      resetPump()
    }
  }

  function toggleEditPump(pump: PumpSummary) {
    if (editingPumpId === pump.pumpId) {
      setEditingPumpId(null)
      setEditPumpError(null)
      resetEdit()
    } else {
      setEditingPumpId(pump.pumpId)
      setEditPumpError(null)
      resetEdit({ pumpName: pump.pumpName, pumpAddress: pump.pumpAddress })
    }
  }

  function onEditPumpSubmit(data: EditPumpFormData) {
    if (!editingPumpId) return
    // Find the pump to preserve its current enabled state
    const pump = owners
      .flatMap(o => o.pumps)
      .find(p => p.pumpId === editingPumpId)
    setEditPumpError(null)
    editPumpMutation.mutate({
      pumpId: editingPumpId,
      data: { pumpName: data.pumpName, pumpAddress: data.pumpAddress, enabled: pump?.enabled ?? true },
    })
  }

  function togglePumpEnabled(pump: PumpSummary) {
    editPumpMutation.mutate({
      pumpId: pump.pumpId,
      data: { pumpName: pump.pumpName, pumpAddress: pump.pumpAddress, enabled: !pump.enabled },
    })
  }

  const handleLogout = () => {
    clearAuth()
    navigate('/login')
  }

  const handleCopyCredentials = () => {
    if (!createdOwner) return
    const text = `PPMS Login Credentials\nPhone: ${createdOwner.phoneNumber}\nPassword: ${createdOwner.password}\nPump: ${createdOwner.pumpName}`
    navigator.clipboard.writeText(text)
  }

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">

      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <header className="bg-gradient-to-r from-slate-900 via-slate-800 to-indigo-900 px-6 py-3 flex items-center justify-between flex-shrink-0 shadow-md">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-indigo-500/20 border border-indigo-400/30 rounded-xl flex items-center justify-center text-2xl select-none">
            ⛽
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-base font-extrabold text-white tracking-wide">PPMS</span>
              <span className="text-xs text-indigo-300 font-medium border border-indigo-500/40 bg-indigo-500/10 px-2 py-0.5 rounded-full">
                Super Admin Portal
              </span>
            </div>
            <p className="text-xs text-slate-400 italic mt-0.5 hidden sm:block">
              Platform management — onboard new pump owners
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="text-right hidden sm:block">
            <p className="text-xs font-semibold text-white leading-tight">{user?.fullName}</p>
            <p className="text-xs text-indigo-300 leading-tight">Super Admin</p>
          </div>
          <div className="w-8 h-8 bg-indigo-500/20 border border-indigo-400/40 rounded-full flex items-center justify-center">
            <span className="text-sm font-bold text-indigo-200">
              {user?.fullName?.charAt(0).toUpperCase()}
            </span>
          </div>
          <button
            onClick={handleLogout}
            className="text-xs text-red-300 hover:text-white border border-red-500/40 hover:border-red-400 hover:bg-red-500/20 px-3 py-1.5 rounded-lg transition-all font-medium"
          >
            Logout
          </button>
        </div>
      </header>

      {/* ── Main content ───────────────────────────────────────────────────── */}
      <div className="flex-1 p-6 overflow-y-auto">
        <div className="max-w-6xl mx-auto">

          {/* ── Platform analytics strip ───────────────────────────────────── */}
          {analytics && (
            <div className="mb-6 grid grid-cols-3 gap-4">
              <div className="bg-white rounded-xl border border-slate-200 px-5 py-4 text-center">
                <p className="text-2xl font-bold text-slate-800">{analytics.totalOwners}</p>
                <p className="text-xs text-slate-500 mt-0.5 uppercase tracking-wide">Pump Owners</p>
              </div>
              <div className="bg-white rounded-xl border border-slate-200 px-5 py-4 text-center">
                <p className="text-2xl font-bold text-slate-800">{analytics.totalPumps}</p>
                <p className="text-xs text-slate-500 mt-0.5 uppercase tracking-wide">Pumps</p>
              </div>
              <div className="bg-white rounded-xl border border-slate-200 px-5 py-4 text-center">
                <p className="text-2xl font-bold text-slate-800">{analytics.totalStaff}</p>
                <p className="text-xs text-slate-500 mt-0.5 uppercase tracking-wide">Staff Members</p>
              </div>
            </div>
          )}

          {/* ── Credentials card (shown after successful onboarding) ───────── */}
          {createdOwner && (
            <div className="mb-6 bg-green-50 border border-green-300 rounded-xl p-5">
              <div className="flex items-start justify-between gap-4">
                <div className="flex items-start gap-3">
                  <span className="text-green-600 text-xl mt-0.5">✓</span>
                  <div>
                    <p className="text-sm font-semibold text-green-800 mb-1">
                      Owner account created — share these login details
                    </p>
                    <p className="text-xs text-green-600 mb-3">
                      {createdOwner.fullName} can now log in and set up their pump.
                    </p>
                    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                      <CredentialField label="Phone" value={createdOwner.phoneNumber} />
                      <div className="bg-white border border-green-200 rounded-lg px-3 py-2">
                        <div className="flex items-center justify-between mb-0.5">
                          <p className="text-xs text-green-600">Password</p>
                          <button
                            onClick={() => setShowPassword(v => !v)}
                            className="text-xs text-green-600 hover:text-green-800 transition-colors"
                            title={showPassword ? 'Hide password' : 'Reveal password'}
                          >
                            {showPassword ? '🙈 Hide' : '👁 Show'}
                          </button>
                        </div>
                        <p className="text-sm font-semibold text-slate-800 font-mono break-all">
                          {showPassword ? createdOwner.password : '••••••••'}
                        </p>
                      </div>
                      <CredentialField label="Pump" value={createdOwner.pumpName} />
                      <CredentialField label="Employee ID" value={createdOwner.employeeId} />
                    </div>
                  </div>
                </div>
                <div className="flex gap-2 flex-shrink-0">
                  <button
                    onClick={handleCopyCredentials}
                    className="text-xs text-green-700 border border-green-400 bg-white hover:bg-green-100 px-3 py-1.5 rounded-lg transition-all font-medium"
                  >
                    Copy
                  </button>
                  <button
                    onClick={() => setCreatedOwner(null)}
                    className="text-xs text-slate-500 hover:text-slate-700 px-2 py-1.5 rounded-lg transition-all"
                  >
                    ✕
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* ── 2-column layout ────────────────────────────────────────────── */}
          <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">

            {/* ── Left: Onboard form ──────────────────────────────────────── */}
            <div className="lg:col-span-2">
              <div className="bg-white rounded-xl border border-slate-200 shadow-sm">
                <div className="px-5 py-4 border-b border-slate-100">
                  <h2 className="text-sm font-semibold text-slate-800">Onboard New Owner</h2>
                  <p className="text-xs text-slate-500 mt-0.5">Creates the owner account and their first pump.</p>
                </div>

                <form onSubmit={handleSubmit(onSubmit)} className="px-5 py-4 space-y-4">

                  <fieldset>
                    <legend className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">
                      Owner Details
                    </legend>
                    <div className="space-y-3">
                      <FormField label="Full Name" error={errors.fullName?.message}>
                        <input
                          {...register('fullName')}
                          type="text"
                          placeholder="e.g. Ramesh Sharma"
                          className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                        />
                      </FormField>

                      <FormField label="Phone Number" error={errors.phoneNumber?.message}>
                        <input
                          {...register('phoneNumber')}
                          type="tel"
                          inputMode="numeric"
                          maxLength={10}
                          placeholder="10-digit mobile number"
                          className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                        />
                      </FormField>

                      <FormField label="Initial Password" error={errors.password?.message}>
                        <input
                          {...register('password')}
                          type="password"
                          placeholder="Min. 6 characters"
                          className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                        />
                      </FormField>
                    </div>
                  </fieldset>

                  <fieldset>
                    <legend className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">
                      Pump Details
                    </legend>
                    <div className="space-y-3">
                      <FormField label="Pump Name" error={errors.pumpName?.message}>
                        <input
                          {...register('pumpName')}
                          type="text"
                          placeholder="e.g. Sharma Petrol Station"
                          className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                        />
                      </FormField>

                      <FormField label="Pump Address" error={errors.pumpAddress?.message}>
                        <input
                          {...register('pumpAddress')}
                          type="text"
                          placeholder="Full address"
                          className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                        />
                      </FormField>

                      <FormField label="Max Nozzle Count" error={errors.maxNozzleCount?.message}>
                        <input
                          {...register('maxNozzleCount')}
                          type="number"
                          min={1}
                          max={20}
                          placeholder="e.g. 4"
                          className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                        />
                      </FormField>
                    </div>
                  </fieldset>

                  {serverError && (
                    <div className="bg-red-50 border border-red-200 rounded-lg p-3">
                      <p className="text-red-600 text-sm">{serverError}</p>
                    </div>
                  )}

                  <button
                    type="submit"
                    disabled={onboardMutation.isPending}
                    className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-300 text-white font-medium py-2 rounded-lg text-sm transition-colors"
                  >
                    {onboardMutation.isPending ? 'Creating...' : 'Create Owner & Pump'}
                  </button>
                </form>
              </div>
            </div>

            {/* ── Right: Owners list ──────────────────────────────────────── */}
            <div className="lg:col-span-3">
              <div className="bg-white rounded-xl border border-slate-200 shadow-sm">
                <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
                  <div>
                    <h2 className="text-sm font-semibold text-slate-800">Onboarded Owners</h2>
                    <p className="text-xs text-slate-500 mt-0.5">All pump owners currently on the platform.</p>
                  </div>
                  <span className="text-xs font-semibold text-indigo-700 bg-indigo-50 border border-indigo-200 px-2.5 py-1 rounded-full">
                    {owners.length} owner{owners.length !== 1 ? 's' : ''}
                  </span>
                </div>

                {ownersLoading ? (
                  <div className="px-5 py-8 text-center text-sm text-slate-400">Loading owners...</div>
                ) : owners.length === 0 ? (
                  <div className="px-5 py-8 text-center">
                    <p className="text-sm text-slate-400">No owners yet.</p>
                    <p className="text-xs text-slate-400 mt-1">Use the form to onboard your first pump owner.</p>
                  </div>
                ) : (() => {
                  const totalPages = Math.max(1, Math.ceil(owners.length / ownersPageSize))
                  const safePage   = Math.min(ownersPage, totalPages - 1)
                  const pageOwners = owners.slice(safePage * ownersPageSize, (safePage + 1) * ownersPageSize)
                  const pagedData  = {
                    content: pageOwners, page: safePage, pageSize: ownersPageSize,
                    totalElements: owners.length, totalPages,
                    hasNext: safePage < totalPages - 1, hasPrevious: safePage > 0,
                  }
                  return (
                    <>
                      <div className="divide-y divide-slate-100">
                        {pageOwners.map((owner) => {
                          const isPumpFormOpen = addPumpOwnerId === owner.ownerId
                          const enabledPumps   = owner.pumps.filter(p => p.enabled).length
                          return (
                            <div key={owner.ownerId}>
                              <div className="px-5 py-4">
                                {/* Owner header row */}
                                <div className="flex items-start justify-between gap-3">
                                  <div className="flex items-center gap-3 min-w-0">
                                    <div className="w-9 h-9 bg-indigo-50 border border-indigo-100 rounded-full flex items-center justify-center flex-shrink-0">
                                      <span className="text-sm font-bold text-indigo-600">
                                        {owner.fullName.charAt(0).toUpperCase()}
                                      </span>
                                    </div>
                                    <div className="min-w-0">
                                      <div className="flex items-center gap-2 flex-wrap">
                                        <p className="text-sm font-semibold text-slate-800">{owner.fullName}</p>
                                        <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
                                          owner.status === 'ACTIVE'
                                            ? 'bg-green-50 text-green-700 border border-green-200'
                                            : 'bg-slate-100 text-slate-500 border border-slate-200'
                                        }`}>
                                          {owner.status.toLowerCase()}
                                        </span>
                                      </div>
                                      <p className="text-xs text-slate-500 font-mono mt-0.5">{owner.phoneNumber}</p>
                                      {/* Pump count summary */}
                                      <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                                        <span className="inline-flex items-center gap-1 text-xs text-slate-500 bg-slate-50 border border-slate-200 px-2 py-0.5 rounded-full">
                                          ⛽ {owner.pumps.length} pump{owner.pumps.length !== 1 ? 's' : ''}
                                          {owner.pumps.length > enabledPumps && (
                                            <span className="text-red-400">· {owner.pumps.length - enabledPumps} disabled</span>
                                          )}
                                        </span>
                                      </div>
                                    </div>
                                  </div>
                                  <div className="flex items-center gap-2 flex-shrink-0">
                                    <button
                                      onClick={() => toggleAddPump(owner.ownerId)}
                                      className={`text-xs font-medium px-2.5 py-1 rounded-lg border transition-colors ${
                                        isPumpFormOpen
                                          ? 'border-indigo-400 text-indigo-700 bg-indigo-50'
                                          : 'border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-700 hover:bg-indigo-50'
                                      }`}
                                    >
                                      {isPumpFormOpen ? '✕ Cancel' : '+ Add Pump'}
                                    </button>
                                  </div>
                                </div>

                                {/* Pumps list */}
                                {owner.pumps.length > 0 && (
                                  <div className="mt-3 ml-12 space-y-2">
                                    {owner.pumps.map((pump) => {
                                      const isEditOpen = editingPumpId === pump.pumpId
                                      return (
                                        <div key={pump.pumpId} className={`rounded-lg border p-2.5 ${
                                          pump.enabled ? 'border-slate-100 bg-slate-50/50' : 'border-red-100 bg-red-50/30 opacity-60'
                                        }`}>
                                          <div className="flex items-start justify-between gap-2">
                                            <div className="flex items-start gap-2 text-xs min-w-0">
                                              <span className="text-slate-400 mt-0.5 flex-shrink-0">⛽</span>
                                              <div className="min-w-0">
                                                <div className="flex items-center gap-1.5 flex-wrap">
                                                  <span className={`font-semibold ${!pump.enabled ? 'line-through text-slate-400' : 'text-slate-700'}`}>
                                                    {pump.pumpName}
                                                  </span>
                                                  {!pump.enabled && (
                                                    <span className="text-xs font-medium text-red-500 bg-red-50 border border-red-200 px-1.5 py-0.5 rounded-full">
                                                      disabled
                                                    </span>
                                                  )}
                                                </div>
                                                <p className="text-slate-400 mt-0.5">{pump.pumpAddress}</p>
                                                {/* Staff count badge */}
                                                <span className="inline-flex items-center gap-1 mt-1 text-xs text-slate-500 bg-white border border-slate-200 px-2 py-0.5 rounded-full">
                                                  👤 {pump.staffCount} staff
                                                </span>
                                              </div>
                                            </div>
                                            <div className="flex items-center gap-1 flex-shrink-0">
                                              <button
                                                onClick={() => toggleEditPump(pump)}
                                                className={`text-xs px-2 py-1 rounded-lg border transition-colors ${
                                                  isEditOpen
                                                    ? 'border-indigo-400 text-indigo-700 bg-indigo-50'
                                                    : 'border-slate-200 text-slate-500 hover:border-indigo-300 hover:text-indigo-600 hover:bg-indigo-50'
                                                }`}
                                              >
                                                {isEditOpen ? '✕' : 'Edit'}
                                              </button>
                                              <button
                                                onClick={() => togglePumpEnabled(pump)}
                                                disabled={editPumpMutation.isPending}
                                                className={`text-xs px-2 py-1 rounded-lg border transition-colors disabled:opacity-40 ${
                                                  pump.enabled
                                                    ? 'border-red-200 text-red-600 hover:bg-red-50'
                                                    : 'border-green-200 text-green-600 hover:bg-green-50'
                                                }`}
                                              >
                                                {pump.enabled ? 'Disable' : 'Enable'}
                                              </button>
                                            </div>
                                          </div>

                                          {/* Inline edit form */}
                                          {isEditOpen && (
                                            <form
                                              onSubmit={handleSubmitEdit(onEditPumpSubmit)}
                                              className="mt-2 p-3 bg-white border border-indigo-100 rounded-lg space-y-2"
                                            >
                                              <FormField label="Pump Name" error={editErrors.pumpName?.message}>
                                                <input
                                                  {...registerEdit('pumpName')}
                                                  type="text"
                                                  className="w-full border border-slate-300 rounded-lg px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500"
                                                />
                                              </FormField>
                                              <FormField label="Pump Address" error={editErrors.pumpAddress?.message}>
                                                <input
                                                  {...registerEdit('pumpAddress')}
                                                  type="text"
                                                  className="w-full border border-slate-300 rounded-lg px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500"
                                                />
                                              </FormField>
                                              {editPumpError && (
                                                <p className="text-xs text-red-600">{editPumpError}</p>
                                              )}
                                              <button
                                                type="submit"
                                                disabled={editPumpMutation.isPending}
                                                className="bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-300 text-white text-xs font-medium px-3 py-1.5 rounded-lg transition-colors"
                                              >
                                                {editPumpMutation.isPending ? 'Saving…' : 'Save Changes'}
                                              </button>
                                            </form>
                                          )}
                                        </div>
                                      )
                                    })}
                                  </div>
                                )}

                                {owner.pumps.length === 0 && (
                                  <p className="ml-12 mt-2 text-xs text-slate-400 italic">No pumps added yet.</p>
                                )}

                                <div className="mt-2 ml-12 flex items-center gap-3 text-xs text-slate-400">
                                  <span>ID: <span className="font-mono text-slate-500">{owner.employeeId}</span></span>
                                  <span>·</span>
                                  <span>Joined {new Date(owner.createdAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}</span>
                                </div>
                              </div>

                              {/* Inline Add Pump form */}
                              {isPumpFormOpen && (
                                <div className="px-5 pb-4 bg-indigo-50/50 border-t border-indigo-100">
                                  <p className="text-xs font-semibold text-indigo-700 pt-3 mb-3">
                                    Add a new pump for {owner.fullName}
                                  </p>
                                  <form onSubmit={handleSubmitPump(onAddPumpSubmit)} className="space-y-3 max-w-sm">
                                    <FormField label="Pump Name" error={pumpErrors.pumpName?.message}>
                                      <input
                                        {...registerPump('pumpName')}
                                        type="text"
                                        placeholder="e.g. Jatin Petrol Station 2"
                                        className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                                      />
                                    </FormField>
                                    <FormField label="Pump Address" error={pumpErrors.pumpAddress?.message}>
                                      <input
                                        {...registerPump('pumpAddress')}
                                        type="text"
                                        placeholder="Full address"
                                        className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                                      />
                                    </FormField>
                                    <FormField label="Max Nozzle Count" error={pumpErrors.maxNozzleCount?.message}>
                                      <input
                                        {...registerPump('maxNozzleCount')}
                                        type="number"
                                        min={1}
                                        max={20}
                                        placeholder="e.g. 4"
                                        className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                                      />
                                    </FormField>
                                    {addPumpError && (
                                      <p className="text-xs text-red-600">{addPumpError}</p>
                                    )}
                                    <button
                                      type="submit"
                                      disabled={addPumpMutation.isPending}
                                      className="bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-300 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
                                    >
                                      {addPumpMutation.isPending ? 'Adding…' : 'Add Pump'}
                                    </button>
                                  </form>
                                </div>
                              )}
                            </div>
                          )
                        })}
                      </div>
                      {owners.length > ownersPageSize && (
                        <div className="px-5 border-t border-slate-100">
                          <Pagination
                            data={pagedData}
                            onPageChange={setOwnersPage}
                            onPageSizeChange={() => {}}
                            pageSizeOptions={[10]}
                          />
                        </div>
                      )}
                    </>
                  )
                })()}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

// ── Small helper components ───────────────────────────────────────────────────

function FormField({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-xs font-medium text-slate-700 mb-1">{label}</label>
      {children}
      {error && <p className="text-red-500 text-xs mt-1">{error}</p>}
    </div>
  )
}

function CredentialField({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-white border border-green-200 rounded-lg px-3 py-2">
      <p className="text-xs text-green-600 mb-0.5">{label}</p>
      <p className="text-sm font-semibold text-slate-800 font-mono break-all">{value}</p>
    </div>
  )
}
