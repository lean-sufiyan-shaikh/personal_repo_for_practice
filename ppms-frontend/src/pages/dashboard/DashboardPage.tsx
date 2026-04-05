import { useState, useEffect, useRef } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { userApi } from '../../api/userApi'
import { pumpApi } from '../../api/pumpApi'
import NotificationBell from '../../components/NotificationBell'
import { canAccessPage } from '../../permissions/permissions'

// Each nav item declares which PAGE_PERMISSIONS key it maps to.
// The sidebar filters this list based on the logged-in user's role at render time.
const ALL_NAV_ITEMS = [
  { label: 'Overview',        path: '/dashboard',                  icon: '🏠', page: 'overview' },
  { label: 'Shifts',          path: '/dashboard/shifts',           icon: '⚡', page: 'shifts' },
  { label: 'Shift Planning',  path: '/dashboard/shift-planning',   icon: '🗓', page: 'shiftPlanning' },
  { label: 'Inventory',       path: '/dashboard/inventory',        icon: '🛢', page: 'inventory' },
  { label: 'Credit',          path: '/dashboard/credit',           icon: '💳', page: 'credit' },
  { label: 'Expenses',        path: '/dashboard/expenses',         icon: '💸', page: 'expenses' },
  { label: 'Documents',       path: '/dashboard/documents',        icon: '📄', page: 'documents' },
  { label: 'Cash Drawer',     path: '/dashboard/cash',             icon: '💰', page: 'cashDrawer' },
  { label: 'Calibration',     path: '/dashboard/calibration',      icon: '🔧', page: 'calibration' },
  { label: 'Payroll',         path: '/dashboard/payroll',          icon: '👷', page: 'payroll' },
  { label: 'Products',        path: '/dashboard/ancillary',        icon: '📦', page: 'products' },
  { label: 'Audit Log',       path: '/dashboard/audit',            icon: '🔍', page: 'auditLog' },
  { label: 'Balance Sheets',  path: '/dashboard/balance-sheets',   icon: '📊', page: 'balanceSheets' },
  { label: 'Reports',         path: '/dashboard/reports',          icon: '📈', page: 'reports' },
  { label: 'Setup',           path: '/dashboard/setup',            icon: '⚙️', page: 'setup' },
]

export default function DashboardPage() {
  const navigate  = useNavigate()
  const location  = useLocation()
  const { user, clearAuth, updateUser } = useAuthStore()

  const { data: pumps = [] } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps })
  const sortedPumps = [...pumps].sort((a, b) => a.name.localeCompare(b.name))
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'

  const { selectedPumpId, setSelectedPumpId } = usePumpStore()

  // Filter nav items to only those the logged-in role is allowed to access
  const NAV_ITEMS = ALL_NAV_ITEMS.filter(item => canAccessPage(item.page, user?.role))

  // Initialise / validate the stored pump whenever the pump list loads.
  // If nothing is stored yet, or the stored pump no longer exists, default to the first pump.
  useEffect(() => {
    if (sortedPumps.length > 0) {
      const valid = sortedPumps.find(p => p.id === selectedPumpId)
      if (!valid) setSelectedPumpId(sortedPumps[0].id)
    }
  }, [sortedPumps.length])

  // Live clock
  const [now, setNow] = useState(new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(timer)
  }, [])

  // Inline name edit
  const [editingName, setEditingName] = useState(false)
  const [nameInput, setNameInput] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const nameInputRef = useRef<HTMLInputElement>(null)

  const renameMutation = useMutation({
    mutationFn: (fullName: string) => userApi.updateMyProfile(fullName),
    onSuccess: (data) => {
      updateUser({ fullName: data.fullName })
      setEditingName(false)
      setNameError(null)
    },
    onError: (err: any) => {
      setNameError(err?.response?.data?.message ?? 'Failed to save name')
    },
  })

  const startEditing = () => {
    setNameInput(user?.fullName ?? '')
    setNameError(null)
    setEditingName(true)
    setTimeout(() => nameInputRef.current?.select(), 0)
  }

  const saveName = () => {
    const trimmed = nameInput.trim()
    if (!trimmed) { setNameError('Name cannot be blank'); return }
    renameMutation.mutate(trimmed)
  }

  const cancelEdit = () => {
    setEditingName(false)
    setNameError(null)
  }

  const handleLogout = () => {
    clearAuth()
    navigate('/login')
  }

  const timeStr = now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true })
  const dateStr = now.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">

      {/* ── Top header ─────────────────────────────────────────────────────────── */}
      <header className="bg-gradient-to-r from-slate-900 via-slate-800 to-blue-900 px-6 py-3 flex items-center justify-between flex-shrink-0 z-10 shadow-md print:hidden">

        {/* ── Left: Logo + tagline ── */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-blue-500/20 border border-blue-400/30 rounded-xl flex items-center justify-center text-2xl select-none">
            ⛽
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-base font-extrabold text-white tracking-wide">PPMS</span>
              <span className="text-xs text-blue-300 font-medium hidden sm:inline border border-blue-500/40 bg-blue-500/10 px-2 py-0.5 rounded-full">
                Petrol Pump Management
              </span>
            </div>
            <p className="text-xs text-slate-400 italic hidden sm:block mt-0.5">
              Fuelling efficiency, every shift. ⚡
            </p>
          </div>
        </div>

        {/* ── Center: Live date/time + pump selector ── */}
        <div className="hidden md:flex flex-col items-center gap-1.5">
          <span className="text-sm font-semibold text-white tabular-nums tracking-wide">{timeStr}</span>
          <span className="text-xs text-slate-400">{dateStr}</span>
          {isOwnerOrAdmin && sortedPumps.length > 0 && (
            <div className="flex gap-1.5">
              {sortedPumps.map(p => (
                <button
                  key={p.id}
                  onClick={() => setSelectedPumpId(p.id)}
                  className={`text-xs px-2.5 py-0.5 rounded-full border font-medium transition-all ${
                    selectedPumpId === p.id
                      ? 'bg-blue-500 border-blue-400 text-white shadow-sm'
                      : 'border-white/20 text-slate-300 hover:border-white/40 hover:text-white'
                  }`}
                >
                  ⛽ {p.name}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* ── Right: Notification bell + User info + logout ── */}
        <div className="flex items-center gap-3">
          {selectedPumpId && <NotificationBell pumpId={selectedPumpId} />}
          <div className="text-right hidden sm:block">
            {editingName ? (
              <div className="flex flex-col items-end gap-1">
                <div className="flex items-center gap-1">
                  <input
                    ref={nameInputRef}
                    value={nameInput}
                    onChange={(e) => setNameInput(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') saveName(); if (e.key === 'Escape') cancelEdit() }}
                    disabled={renameMutation.isPending}
                    className="text-xs font-semibold bg-white/10 text-white border border-white/30 rounded px-2 py-0.5 w-36 focus:outline-none focus:border-blue-400"
                    autoFocus
                  />
                  <button
                    onClick={saveName}
                    disabled={renameMutation.isPending}
                    className="text-green-400 hover:text-green-300 text-xs font-bold disabled:opacity-50"
                    title="Save"
                  >✓</button>
                  <button
                    onClick={cancelEdit}
                    disabled={renameMutation.isPending}
                    className="text-slate-400 hover:text-slate-200 text-xs disabled:opacity-50"
                    title="Cancel"
                  >✕</button>
                </div>
                {nameError && <p className="text-red-400 text-xs">{nameError}</p>}
              </div>
            ) : (
              <button
                onClick={startEditing}
                className="group text-right"
                title="Click to edit your name"
              >
                <p className="text-xs font-semibold text-white leading-tight group-hover:text-blue-200 transition-colors">
                  {user?.fullName}
                  <span className="ml-1 text-white/30 group-hover:text-blue-300 text-xs">✎</span>
                </p>
              </button>
            )}
            <p className="text-xs text-blue-300 leading-tight capitalize">{user?.role?.toLowerCase()}</p>
          </div>
          <div className="w-8 h-8 bg-blue-500/20 border border-blue-400/40 rounded-full flex items-center justify-center">
            <span className="text-sm font-bold text-blue-200">
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

      {/* ── Body ───────────────────────────────────────────────────────────────── */}
      <div className="flex flex-1 overflow-hidden">

        {/* ── Sidebar ── */}
        <nav className="w-52 flex-shrink-0 bg-white border-r border-slate-200 flex flex-col overflow-y-auto print:hidden">
          <div className="py-2 px-2 flex-1">
            {NAV_ITEMS.map((item) => {
              const isActive =
                location.pathname === item.path ||
                (item.path !== '/dashboard' && location.pathname.startsWith(item.path))
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={`flex items-center gap-2 px-3 py-1.5 text-sm font-medium rounded-lg mb-0.5 transition-all ${
                    isActive
                      ? 'bg-blue-50 text-blue-700'
                      : 'text-slate-600 hover:bg-slate-50 hover:text-slate-800'
                  }`}
                >
                  {/* Active indicator bar */}
                  <span className={`w-0.5 h-3 rounded-full flex-shrink-0 transition-all ${isActive ? 'bg-blue-600' : 'bg-transparent'}`} />
                  <span className="text-sm leading-none">{item.icon}</span>
                  <span className="truncate">{item.label}</span>
                </Link>
              )
            })}
          </div>
        </nav>

        {/* ── Main content ── */}
        <main className="flex-1 min-w-0 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
