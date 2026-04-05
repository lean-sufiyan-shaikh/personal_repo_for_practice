import { useState } from 'react'
import { useQuery, useMutation, useQueryClient, useQueries } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { creditApi } from '../../api/creditApi'
import type { CreditClient, CreditTransaction, RecordPaymentRequest, UpdateInterestSettingsRequest, CreditExtension, CreateExtensionRequest, CreditExtensionType } from '../../api/creditApi'
import { pumpApi } from '../../api/pumpApi'
import type { PagedResponse } from '../../types/paged'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'

const today     = new Date().toISOString().slice(0, 10)
const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)

// ─── Record Payment Modal ────────────────────────────────────────────────────

interface RecordPaymentModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

function RecordPaymentModal({ client, pumpId, onClose }: RecordPaymentModalProps) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [paymentMode, setPaymentMode] = useState<'CASH' | 'UPI' | 'BANK_TRANSFER' | 'OTHER'>('CASH')
  const [paidAt, setPaidAt] = useState(() => new Date().toISOString().slice(0, 10))
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: RecordPaymentRequest) =>
      creditApi.recordPayment(pumpId, client.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId, client.id] })
      onClose()
    },
    onError: (err: any) => {
      setError(err?.response?.data?.message ?? 'Failed to record payment')
    },
  })

  const handleSubmit = () => {
    const amt = parseFloat(amount)
    if (!amount || isNaN(amt) || amt <= 0) {
      setError('Please enter a valid amount')
      return
    }
    if (amt > client.outstandingBalance) {
      setError(`Amount cannot exceed outstanding balance (₹${client.outstandingBalance.toLocaleString('en-IN', { minimumFractionDigits: 2 })})`)
      return
    }
    setError(null)
    mutation.mutate({ amount: amt, paymentMode, paidAt, notes: notes.trim() || undefined })
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-800">Record Payment</h2>
            <p className="text-sm text-slate-500 mt-0.5">{client.name}</p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
        </div>

        <div className="px-6 py-4 space-y-4">
          {/* Outstanding + Remaining live feedback */}
          <div className="rounded-lg border border-amber-200 overflow-hidden">
            <div className="bg-amber-50 px-4 py-3 flex items-center justify-between">
              <span className="text-sm text-amber-700">Outstanding Balance</span>
              <span className="text-base font-bold text-amber-800">
                ₹{client.outstandingBalance.toLocaleString('en-IN', { minimumFractionDigits: 2 })}
              </span>
            </div>
            {amount !== '' && (() => {
              const amt = parseFloat(amount) || 0
              const remaining = client.outstandingBalance - amt
              return (
                <div className={`px-4 py-2.5 flex items-center justify-between border-t ${
                  remaining >= 0 ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-100'
                }`}>
                  <span className={`text-sm ${remaining >= 0 ? 'text-green-700' : 'text-red-600'}`}>
                    Remaining after payment
                  </span>
                  <span className={`text-sm font-bold ${remaining >= 0 ? 'text-green-800' : 'text-red-700'}`}>
                    ₹{Math.abs(remaining).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                    {remaining < 0 ? ' (over)' : ''}
                  </span>
                </div>
              )
            })()}
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Amount <span className="text-red-500">*</span>
            </label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-sm">₹</span>
              <input
                type="number"
                min="0.01"
                step="0.01"
                value={amount}
                onChange={e => setAmount(e.target.value)}
                placeholder="0.00"
                autoFocus
                className="w-full pl-7 pr-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Payment Mode <span className="text-red-500">*</span>
            </label>
            <SearchableSelect
              value={paymentMode}
              onChange={v => setPaymentMode(v as typeof paymentMode)}
              options={[
                { value: 'CASH',          label: 'Cash' },
                { value: 'UPI',           label: 'UPI' },
                { value: 'BANK_TRANSFER', label: 'Bank Transfer' },
                { value: 'OTHER',         label: 'Other' },
              ]}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">
              Payment Date <span className="text-red-500">*</span>
            </label>
            <input
              type="date"
              value={paidAt}
              onChange={e => setPaidAt(e.target.value)}
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">Notes</label>
            <input
              type="text"
              value={notes}
              onChange={e => setNotes(e.target.value)}
              placeholder="e.g. Paid via NEFT ref TXN12345"
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {error && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
              {error}
            </div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-200 flex justify-end gap-3">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-slate-600 border border-slate-300 rounded-lg hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="px-4 py-2 text-sm font-medium text-white bg-green-600 rounded-lg hover:bg-green-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Saving…' : 'Record Payment'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Set Credit Limit Modal ──────────────────────────────────────────────────

interface SetCreditLimitModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

function SetCreditLimitModal({ client, pumpId, onClose }: SetCreditLimitModalProps) {
  const qc = useQueryClient()
  const [limit, setLimit] = useState(client.creditLimit > 0 ? String(client.creditLimit) : '')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (creditLimit: number) =>
      creditApi.updateCreditLimit(pumpId, client.id, { creditLimit }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      onClose()
    },
    onError: (err: any) => {
      setError(err?.response?.data?.message ?? 'Failed to update credit limit')
    },
  })

  const handleSubmit = () => {
    const val = limit.trim() === '' ? 0 : parseFloat(limit)
    if (isNaN(val) || val < 0) {
      setError('Credit limit must be 0 or a positive number')
      return
    }
    setError(null)
    mutation.mutate(val)
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-sm">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-800">Set Credit Limit</h2>
            <p className="text-sm text-slate-500 mt-0.5">{client.name}</p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
        </div>

        <div className="px-6 py-4 space-y-4">
          <p className="text-sm text-slate-500">
            Enter the maximum credit balance allowed. Set to <strong>0</strong> to allow unlimited credit.
          </p>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">Credit Limit (₹)</label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-sm">₹</span>
              <input
                type="number"
                min="0"
                step="0.01"
                value={limit}
                onChange={e => setLimit(e.target.value)}
                placeholder="0 = unlimited"
                className="w-full pl-7 pr-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>

          {error && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
              {error}
            </div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-200 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-slate-600 border border-slate-300 rounded-lg hover:bg-slate-50">
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Saving…' : 'Save Limit'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Set Interest Settings Modal ─────────────────────────────────────────────

interface SetInterestSettingsModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

function SetInterestSettingsModal({ client, pumpId, onClose }: SetInterestSettingsModalProps) {
  const qc = useQueryClient()
  const [rate, setRate] = useState(client.monthlyInterestRate > 0 ? String(client.monthlyInterestRate) : '')
  const [graceDays, setGraceDays] = useState(String(client.interestGraceDays ?? 1))
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: UpdateInterestSettingsRequest) =>
      creditApi.updateInterestSettings(pumpId, client.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      onClose()
    },
    onError: (err: any) => setError(err?.response?.data?.message ?? 'Failed to update interest settings'),
  })

  const handleSubmit = () => {
    const r = rate.trim() === '' ? 0 : parseFloat(rate)
    const g = parseInt(graceDays, 10)
    if (isNaN(r) || r < 0 || r > 100) { setError('Rate must be between 0 and 100'); return }
    if (isNaN(g) || g < 0) { setError('Grace days must be 0 or more'); return }
    setError(null)
    mutation.mutate({ monthlyInterestRate: r, interestGraceDays: g })
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-sm">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-800">Interest Settings</h2>
            <p className="text-sm text-slate-500 mt-0.5">{client.name}</p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
        </div>

        <div className="px-6 py-4 space-y-4">
          <p className="text-sm text-slate-500">
            Simple interest is calculated as: <strong>outstanding × rate × (days / 30)</strong>.
            Set rate to <strong>0</strong> to disable interest for this client.
          </p>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">Monthly Interest Rate (%)</label>
            <div className="relative">
              <input
                type="number" min="0" max="100" step="0.01"
                value={rate}
                onChange={e => setRate(e.target.value)}
                placeholder="0 = no interest"
                className="w-full pr-8 pl-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <span className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 text-sm">%</span>
            </div>
            <p className="text-xs text-slate-400 mt-1">
              e.g. 2% means ₹100 outstanding → ₹2 interest per month
            </p>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1">Grace Period (days)</label>
            <input
              type="number" min="0" step="1"
              value={graceDays}
              onChange={e => setGraceDays(e.target.value)}
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <p className="text-xs text-slate-400 mt-1">
              Interest starts this many days after the first credit sale. Default is 1.
            </p>
          </div>

          {error && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-200 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-slate-600 border border-slate-300 rounded-lg hover:bg-slate-50">
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Saving…' : 'Save Settings'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Helpers for week grouping ───────────────────────────────────────────────

function getWeekKey(iso: string): string {
  const d = new Date(iso)
  const day = d.getDay()
  const diff = d.getDate() - day + (day === 0 ? -6 : 1) // Monday
  const mon = new Date(d); mon.setDate(diff)
  const sun = new Date(mon); sun.setDate(mon.getDate() + 6)
  const fmt = (dt: Date) =>
    dt.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })
  return `${fmt(mon)} – ${fmt(sun)}`
}

function getMonthKey(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' })
}

// ─── Grant Extension Modal ───────────────────────────────────────────────────

interface GrantExtensionModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

const EXTENSION_TYPE_OPTIONS = [
  { value: 'AMOUNT_EXTENSION',       label: 'Amount Extension — extra credit headroom' },
  { value: 'BILLING_CYCLE_EXTENSION', label: 'Billing Cycle Extension — defer overdue block' },
  { value: 'OVERDUE_BLOCK_WAIVER',   label: 'Overdue Block Waiver — one-time waiver' },
]

function GrantExtensionModal({ client, pumpId, onClose }: GrantExtensionModalProps) {
  const qc = useQueryClient()
  const [extensionType, setExtensionType] = useState<CreditExtensionType>('BILLING_CYCLE_EXTENSION')
  const [extensionAmount, setExtensionAmount] = useState('')
  const [expiryDate, setExpiryDate] = useState(today)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  // Minimum expiry = tomorrow
  const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1)
  const minDate = tomorrow.toISOString().slice(0, 10)

  const mutation = useMutation({
    mutationFn: (req: CreateExtensionRequest) =>
      creditApi.createExtension(pumpId, client.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-extensions', pumpId, client.id] })
      qc.invalidateQueries({ queryKey: ['extensions', pumpId, client.id] })
      onClose()
    },
    onError: (err: any) => setError(err?.response?.data?.message ?? 'Failed to create extension'),
  })

  const handleSubmit = () => {
    if (!expiryDate) { setError('Expiry date is required'); return }
    if (!reason.trim()) { setError('A reason is required'); return }
    if (extensionType === 'AMOUNT_EXTENSION') {
      const amt = parseFloat(extensionAmount)
      if (!extensionAmount || isNaN(amt) || amt <= 0) { setError('A positive amount is required for Amount Extension'); return }
    }
    setError(null)
    mutation.mutate({
      extensionType,
      extensionAmount: extensionType === 'AMOUNT_EXTENSION' ? parseFloat(extensionAmount) : undefined,
      expiryDate,
      reason: reason.trim(),
    })
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-800">Grant Credit Extension</h2>
            <p className="text-sm text-slate-500 mt-0.5">{client.name}</p>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
        </div>

        <div className="px-6 py-4 space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1.5">Extension Type</label>
            <SearchableSelect
              value={extensionType}
              onChange={(v) => setExtensionType(v as CreditExtensionType)}
              options={EXTENSION_TYPE_OPTIONS}
            />
          </div>

          {extensionType === 'AMOUNT_EXTENSION' && (
            <div>
              <label className="block text-xs font-medium text-slate-600 mb-1">
                Additional Credit Amount (₹) <span className="text-red-500">*</span>
              </label>
              <input
                type="number" min="1" step="0.01"
                value={extensionAmount}
                onChange={(e) => setExtensionAmount(e.target.value)}
                placeholder="e.g. 5000"
                className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <p className="text-xs text-slate-400 mt-1">
                Current limit: {client.creditLimit > 0 ? `₹${client.creditLimit.toLocaleString('en-IN')}` : 'Unlimited'}
              </p>
            </div>
          )}

          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">
              Expires On <span className="text-red-500">*</span>
            </label>
            <input
              type="date" min={minDate}
              value={expiryDate}
              onChange={(e) => setExpiryDate(e.target.value)}
              className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">
              Reason / Justification <span className="text-red-500">*</span>
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              placeholder="Why is this extension being granted?"
              className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
            />
          </div>

          {error && (
            <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">{error}</div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-200 flex justify-end gap-3">
          <button onClick={onClose} className="px-4 py-2 text-sm text-slate-600 border border-slate-300 rounded-lg hover:bg-slate-50">
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Granting…' : 'Grant Extension'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Reassign Credit Entry Modal ─────────────────────────────────────────────

interface ReassignModalProps {
  entryId: number
  currentClientId: number
  pumpId: number
  onClose: () => void
}

function ReassignCreditEntryModal({ entryId, currentClientId, pumpId, onClose }: ReassignModalProps) {
  const qc = useQueryClient()
  const [toClientId, setToClientId] = useState<string>('')
  const [reason, setReason]         = useState('')
  const [error, setError]           = useState<string | null>(null)

  const { data: allClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn:  () => pumpApi.getCreditClients(pumpId),
  })

  // Restrict targets to within the same parent/child group only:
  // • Parent account   → can move only to its own sub-accounts
  // • Sub-account      → can move to its parent OR sibling sub-accounts
  // • Standalone       → no valid targets (no group to move within)
  const currentClient = allClients.find(c => c.id === currentClientId)

  let targets: typeof allClients = []
  let noTargetsReason: string | null = null

  if (currentClient?.isParent) {
    targets = allClients.filter(c => c.parentClientId === currentClientId)
    if (targets.length === 0) {
      noTargetsReason = 'This account has no sub-accounts yet. Add sub-accounts in Setup first.'
    }
  } else if (currentClient?.parentClientId !== null && currentClient?.parentClientId !== undefined) {
    targets = allClients.filter(c =>
      c.id !== currentClientId &&
      (c.id === currentClient.parentClientId || c.parentClientId === currentClient.parentClientId)
    )
  } else {
    noTargetsReason = 'Entries can only be moved within a parent / sub-account group. This is a standalone account with no group.'
  }

  const mutation = useMutation({
    mutationFn: ({ toId, rsn }: { toId: number; rsn: string }) =>
      pumpApi.reassignCreditEntry(pumpId, entryId, toId, rsn),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId] })
      onClose()
    },
    onError: (err: any) => setError(err?.response?.data?.message ?? 'Failed to reassign entry'),
  })

  const handleSubmit = () => {
    if (!toClientId)        { setError('Select a target client'); return }
    if (!reason.trim())     { setError('Reason is required'); return }
    setError(null)
    mutation.mutate({ toId: Number(toClientId), rsn: reason.trim() })
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-sm">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <h2 className="text-base font-semibold text-slate-800">Move Credit Entry</h2>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
        </div>
        <div className="px-6 py-4 space-y-4">
          {noTargetsReason ? (
            <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3">
              <p className="text-sm text-amber-800">{noTargetsReason}</p>
            </div>
          ) : (
            <>
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1.5">
                  Move to <span className="text-red-500">*</span>
                </label>
                <SearchableSelect
                  value={toClientId}
                  onChange={setToClientId}
                  placeholder="Search and select account…"
                  searchThreshold={0}
                  options={targets.map(c => ({
                    value: String(c.id),
                    label: c.parentClientId === null ? `${c.name} (parent)` : c.name,
                  }))}
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-600 mb-1">
                  Reason <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={reason}
                  onChange={e => setReason(e.target.value)}
                  rows={2}
                  placeholder="e.g. Entry was logged under parent but belongs to sub-account"
                  className="w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
                />
              </div>
              {error && <p className="text-red-500 text-sm">{error}</p>}
            </>
          )}
        </div>
        <div className="px-6 py-4 border-t border-slate-100 flex gap-3">
          <button onClick={onClose}
            className="flex-1 border border-slate-300 text-slate-700 py-2 rounded-lg text-sm font-medium hover:bg-slate-50">
            {noTargetsReason ? 'Close' : 'Cancel'}
          </button>
          {!noTargetsReason && (
            <button onClick={handleSubmit} disabled={mutation.isPending}
              className="flex-1 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white py-2 rounded-lg text-sm font-medium">
              {mutation.isPending ? 'Moving…' : 'Move Entry'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

// ─── Client Detail (transaction history) ────────────────────────────────────

interface ClientDetailProps {
  clientId: number
  pumpId: number
  isOwnerOrAdmin: boolean
  onBack: () => void
}

function ClientDetail({ clientId, pumpId, isOwnerOrAdmin, onBack }: ClientDetailProps) {
  const [showPaymentModal, setShowPaymentModal] = useState(false)
  const [showInterestSettingsModal, setShowInterestSettingsModal] = useState(false)
  const [showExtensionModal, setShowExtensionModal] = useState(false)
  const [applyInterestFeedback, setApplyInterestFeedback] = useState<string | null>(null)
  const [confirmDeleteInterestId, setConfirmDeleteInterestId] = useState<number | null>(null)
  const [reassignEntryId,         setReassignEntryId]         = useState<number | null>(null)
  // Track which client the entry being reassigned belongs to (may be a child in parent view)
  const [reassignSourceClientId,  setReassignSourceClientId]  = useState<number>(clientId)
  // Tracks which sections are expanded in the parent ledger view.
  // 'own' (parent's own transactions) starts expanded; sub-account sections start collapsed.
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(['own']))
  const toggleSection = (key: string) =>
    setExpandedSections(prev => { const next = new Set(prev); next.has(key) ? next.delete(key) : next.add(key); return next })
  const qc = useQueryClient()

  // Subscribe to the ledger summary so it refreshes automatically after a payment
  const { data: allClients = [] } = useQuery({
    queryKey: ['credit-ledger', pumpId],
    queryFn: () => creditApi.getLedgerSummary(pumpId),
  })
  const client = allClients.find(c => c.id === clientId)

  // Full client list (needed to discover children, which may have 0 balance and not appear in ledger)
  const { data: allPumpClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn:  () => pumpApi.getCreditClients(pumpId),
    enabled:  client?.isParent === true,
  })
  const childClients = allPumpClients.filter(c => c.parentClientId === clientId)

  // Active credit extensions for this client
  const { data: activeExtensions = [] } = useQuery<CreditExtension[]>({
    queryKey: ['active-extensions', pumpId, clientId],
    queryFn: () => creditApi.getActiveExtensions(pumpId, clientId),
    enabled: !!client,
  })

  const [txPage, setTxPage] = useState(0)
  const [txPageSize, setTxPageSize] = useState(10)

  // For parent accounts we load a large page so all sub-account data can be merged and sorted
  // client-side. Parent accounts at a petrol pump rarely exceed a few hundred transactions.
  const isParent = client?.isParent === true
  const { data: ownTxPage, isLoading } = useQuery({
    queryKey: ['credit-transactions', pumpId, clientId, isParent ? 0 : txPage, isParent ? 1000 : txPageSize],
    queryFn: () => creditApi.getTransactions(pumpId, clientId, isParent ? 0 : txPage, isParent ? 1000 : txPageSize),
  })
  const ownTransactions = ownTxPage?.content ?? []

  const { data: totalInterestRecovered = 0 } = useQuery({
    queryKey: ['credit-interest-recovered', pumpId, clientId],
    queryFn: () => creditApi.getTotalInterestRecovered(pumpId, clientId),
    enabled: !!pumpId && !!clientId,
  })

  // Fetch each child's transactions in parallel (only runs when viewing a parent account)
  // Large page size since this is already a complex merged view
  const childTxQueries = useQueries({
    queries: childClients.map(child => ({
      queryKey: ['credit-transactions', pumpId, child.id, 0, 1000],
      queryFn:  (): Promise<PagedResponse<CreditTransaction>> => creditApi.getTransactions(pumpId, child.id, 0, 1000),
    })),
  })

  // Extended transaction type that carries sub-account metadata for the merged parent view
  type MergedTx = CreditTransaction & { subAccountName?: string; sourceClientId: number }

  const transactions: MergedTx[] = isParent
    ? [
        ...ownTransactions.map(tx => ({ ...tx, sourceClientId: clientId })),
        ...childTxQueries.flatMap((q, i) =>
          ((q.data?.content) ?? []).map(tx => ({
            ...tx,
            subAccountName: childClients[i]?.name,
            sourceClientId: childClients[i]?.id ?? clientId,
          }))
        ),
      ].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
    : ownTransactions.map(tx => ({ ...tx, sourceClientId: clientId }))

  const applyInterestMutation = useMutation({
    mutationFn: () => creditApi.applyInterest(pumpId, clientId),
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId, clientId] })
      if (result.applied) {
        setApplyInterestFeedback(`₹${result.amount?.toFixed(2)} interest applied for ${result.days} days (${result.periodFrom} – ${result.periodTo})`)
      } else {
        setApplyInterestFeedback(result.reason ?? 'No interest applicable')
      }
    },
    onError: (err: any) => setApplyInterestFeedback(err?.response?.data?.message ?? 'Failed to apply interest'),
  })

  const deleteInterestMutation = useMutation({
    mutationFn: (chargeId: number) => creditApi.deleteInterestCharge(pumpId, clientId, chargeId),
    onSuccess: () => {
      setConfirmDeleteInterestId(null)
      qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId, clientId] })
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
    },
    onError: () => setConfirmDeleteInterestId(null),
  })

  // ── Filter state ──────────────────────────────────────────────────────────
  type FilterMode = 'all' | 'month' | 'range'
  const [filterMode, setFilterMode] = useState<FilterMode>('all')
  const [selectedMonth, setSelectedMonth] = useState<string>('')
  const [fromDate, setFromDate]         = useState(yesterday)
  const [toDate, setToDate]             = useState(today)

  // Unique months derived from transactions (for the month picker)
  const availableMonths = Array.from(
    new Set(transactions.map(tx => getMonthKey(tx.occurredAt)))
  )

  // Apply filter
  const filtered = transactions.filter(tx => {
    if (filterMode === 'month' && selectedMonth) {
      return getMonthKey(tx.occurredAt) === selectedMonth
    }
    if (filterMode === 'range') {
      const d = tx.occurredAt.slice(0, 10)
      if (fromDate && d < fromDate) return false
      if (toDate   && d > toDate)   return false
    }
    return true
  })

  // Helper: groups a sorted array of transactions into week buckets
  const buildWeekGroups = <T extends CreditTransaction>(txs: T[]): Array<{ label: string; items: T[] }> => {
    const groups: Array<{ label: string; items: T[] }> = []
    txs.forEach(tx => {
      const label = getWeekKey(tx.occurredAt)
      const last = groups[groups.length - 1]
      if (last && last.label === label) { last.items.push(tx) }
      else { groups.push({ label, items: [tx] }) }
    })
    return groups
  }

  // Group filtered transactions by week (already sorted newest→oldest from API)
  const weekGroups = buildWeekGroups(filtered)

  // For parent view: apply the same filter to each child's transactions and to parent's own
  const childFilteredTxs = childClients.map((_, i) => {
    const childTxs = (childTxQueries[i]?.data ?? []) as MergedTx[]
    return childTxs.filter(tx => {
      if (filterMode === 'month' && selectedMonth) return getMonthKey(tx.occurredAt) === selectedMonth
      if (filterMode === 'range') {
        const d = tx.occurredAt.slice(0, 10)
        if (fromDate && d < fromDate) return false
        if (toDate   && d > toDate)   return false
      }
      return true
    })
  })
  const filteredOwn = ownTransactions.filter(tx => {
    if (filterMode === 'month' && selectedMonth) return getMonthKey(tx.occurredAt) === selectedMonth
    if (filterMode === 'range') {
      const d = tx.occurredAt.slice(0, 10)
      if (fromDate && d < fromDate) return false
      if (toDate   && d > toDate)   return false
    }
    return true
  })

  const formatDate = (iso: string) =>
    new Date(iso).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })

  const formatAmount = (n: number) =>
    n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  // Inner rendering helper — renders week group cards for a set of transactions.
  // srcClientId is passed explicitly so the Move action knows which client owns the entry.
  const renderWeekGroups = (
    groups: Array<{ label: string; items: CreditTransaction[] }>,
    srcClientId: number
  ) => groups.map(({ label, items }) => {
    const weekSales    = items.filter(t => t.type === 'SALE').reduce((s, t) => s + t.amount, 0)
    const weekPayments = items.filter(t => t.type === 'PAYMENT').reduce((s, t) => s + t.amount, 0)
    const weekInterest = items.filter(t => t.type === 'INTEREST').reduce((s, t) => s + t.amount, 0)
    return (
      <div key={label} className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="px-4 py-2.5 bg-slate-50 border-b border-slate-100 flex items-center justify-between">
          <span className="text-xs font-semibold text-slate-600">{label}</span>
          <div className="flex items-center gap-4 text-xs">
            {weekSales    > 0 && <span className="text-orange-600">+₹{formatAmount(weekSales)} sales</span>}
            {weekInterest > 0 && <span className="text-purple-600">+₹{formatAmount(weekInterest)} interest</span>}
            {weekPayments > 0 && <span className="text-green-600">−₹{formatAmount(weekPayments)} paid</span>}
          </div>
        </div>
        <table className="w-full text-sm">
          <tbody className="divide-y divide-slate-50">
            {items.map((tx, idx) => (
              <tr key={`${tx.type}-${tx.referenceId}-${idx}`} className="hover:bg-slate-50">
                <td className="px-4 py-2.5 text-slate-500 text-xs whitespace-nowrap w-20">
                  {formatDate(tx.occurredAt)}
                </td>
                <td className="px-2 py-2.5 w-24">
                  <span className={`inline-flex text-xs font-medium px-2 py-0.5 rounded-full ${
                    tx.type === 'SALE' ? 'bg-orange-100 text-orange-700'
                    : tx.type === 'INTEREST' ? 'bg-purple-100 text-purple-700'
                    : 'bg-green-100 text-green-700'
                  }`}>
                    {tx.type === 'SALE' ? '↑ Sale' : tx.type === 'INTEREST' ? '% Interest' : '↓ Paid'}
                  </span>
                </td>
                <td className="px-2 py-2.5 text-xs max-w-[160px]">
                  <span className="text-slate-500 truncate block">{tx.reference || tx.detail || '—'}</span>
                </td>
                <td className={`px-2 py-2.5 text-right text-xs font-semibold ${
                  tx.type === 'PAYMENT' ? 'text-green-600' : tx.type === 'INTEREST' ? 'text-purple-600' : 'text-orange-600'
                }`}>
                  {tx.type === 'PAYMENT' ? '−' : '+'}₹{formatAmount(tx.amount)}
                </td>
                <td className={`px-4 py-2.5 text-right text-xs font-bold w-28 ${
                  tx.runningBalance > 0 ? 'text-slate-700' : 'text-green-600'
                }`}>
                  ₹{formatAmount(tx.runningBalance)}
                </td>
                <td className="px-3 py-2.5 w-28 text-right">
                  {isOwnerOrAdmin && tx.type === 'SALE' && (
                    <button
                      onClick={() => { setReassignEntryId(tx.referenceId); setReassignSourceClientId(srcClientId) }}
                      className="text-xs text-blue-400 hover:text-blue-600 transition-colors font-medium"
                      title="Move this entry within the account group"
                    >
                      Move
                    </button>
                  )}
                  {isOwnerOrAdmin && tx.type === 'INTEREST' && (
                    confirmDeleteInterestId === tx.referenceId ? (
                      <span className="inline-flex items-center gap-1">
                        <button onClick={() => deleteInterestMutation.mutate(tx.referenceId)} disabled={deleteInterestMutation.isPending}
                          className="text-xs text-white bg-red-500 hover:bg-red-600 disabled:opacity-50 px-2 py-0.5 rounded font-medium">
                          {deleteInterestMutation.isPending ? '…' : 'Yes'}
                        </button>
                        <button onClick={() => setConfirmDeleteInterestId(null)}
                          className="text-xs text-slate-500 hover:text-slate-700 px-2 py-0.5 rounded border border-slate-200 hover:bg-slate-100">
                          No
                        </button>
                      </span>
                    ) : (
                      <button onClick={() => setConfirmDeleteInterestId(tx.referenceId)}
                        className="text-xs text-red-400 hover:text-red-600 transition-colors font-medium"
                        title="Remove this interest charge">
                        Remove
                      </button>
                    )
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  })

  if (!client) {
    return <div className="p-6 text-sm text-slate-400">Loading client…</div>
  }

  return (
    <div>
      {showPaymentModal && (
        <RecordPaymentModal
          client={client}
          pumpId={pumpId}
          onClose={() => setShowPaymentModal(false)}
        />
      )}
      {reassignEntryId !== null && isOwnerOrAdmin && (
        <ReassignCreditEntryModal
          entryId={reassignEntryId}
          currentClientId={reassignSourceClientId}
          pumpId={pumpId}
          onClose={() => { setReassignEntryId(null) }}
        />
      )}
      {showInterestSettingsModal && (
        <SetInterestSettingsModal
          client={client}
          pumpId={pumpId}
          onClose={() => setShowInterestSettingsModal(false)}
        />
      )}
      {showExtensionModal && isOwnerOrAdmin && (
        <GrantExtensionModal
          client={client}
          pumpId={pumpId}
          onClose={() => setShowExtensionModal(false)}
        />
      )}

      {/* Header */}
      <div className="p-6 pb-4">
        <button
          onClick={onBack}
          className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 mb-4"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
          Back to all clients
        </button>

        <div className="flex items-start justify-between flex-wrap gap-3">
          <div>
            <h2 className="text-xl font-bold text-slate-800">{client.name}</h2>
            <p className="text-sm text-slate-500 mt-0.5">{client.phone}</p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {isOwnerOrAdmin && (
              <button
                onClick={() => setShowInterestSettingsModal(true)}
                className="px-3 py-2 text-sm font-medium text-purple-700 border border-purple-200 bg-white rounded-lg hover:bg-purple-50"
              >
                Interest Settings
              </button>
            )}
            {client.monthlyInterestRate > 0 && client.outstandingBalance > 0 && (
              <button
                onClick={() => { setApplyInterestFeedback(null); applyInterestMutation.mutate() }}
                disabled={applyInterestMutation.isPending}
                className="px-3 py-2 text-sm font-medium text-white bg-purple-600 rounded-lg hover:bg-purple-700 disabled:opacity-50"
              >
                {applyInterestMutation.isPending ? 'Applying…' : 'Apply Interest'}
              </button>
            )}
            {isOwnerOrAdmin && (
              <button
                onClick={() => setShowExtensionModal(true)}
                className="px-3 py-2 text-sm font-medium text-blue-700 border border-blue-200 bg-white rounded-lg hover:bg-blue-50"
              >
                Grant Extension
              </button>
            )}
            {client.outstandingBalance > 0 && (
              <button
                onClick={() => setShowPaymentModal(true)}
                className="px-4 py-2 text-sm font-medium text-white bg-green-600 rounded-lg hover:bg-green-700"
              >
                Record Payment
              </button>
            )}
          </div>
        </div>

        {applyInterestFeedback && (
          <div className="mt-3 text-sm bg-purple-50 border border-purple-200 rounded-lg px-4 py-2.5 text-purple-800 flex items-center justify-between">
            <span>{applyInterestFeedback}</span>
            <button onClick={() => setApplyInterestFeedback(null)} className="ml-2 text-purple-400 hover:text-purple-600">×</button>
          </div>
        )}
        {isOwnerOrAdmin && client.monthlyInterestRate === 0 && client.outstandingBalance > 0 && (
          <div className="mt-3 text-sm bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-slate-500 flex items-center gap-2">
            <span>No interest rate configured.</span>
            <button
              onClick={() => setShowInterestSettingsModal(true)}
              className="text-purple-600 font-medium hover:text-purple-800 underline-offset-2 hover:underline"
            >
              Set interest rate
            </button>
          </div>
        )}

        {/* Active extension banners */}
        {activeExtensions.map((ext: CreditExtension) => (
          <div key={ext.id} className="mt-3 text-sm bg-blue-50 border border-blue-200 rounded-lg px-4 py-2.5 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="font-medium text-blue-800">
                {ext.extensionType === 'AMOUNT_EXTENSION' && `+₹${ext.extensionAmount?.toLocaleString('en-IN')} credit headroom`}
                {ext.extensionType === 'BILLING_CYCLE_EXTENSION' && 'Billing cycle overdue block deferred'}
                {ext.extensionType === 'OVERDUE_BLOCK_WAIVER' && 'Overdue block waived'}
              </span>
              <span className="text-xs text-blue-500">expires {ext.expiryDate}</span>
            </div>
            <span className="text-xs text-blue-400 italic truncate max-w-xs">{ext.reason}</span>
          </div>
        ))}
      </div>

      {/* Summary cards */}
      <div className="px-6 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4 mb-6">
        <div className="bg-white rounded-xl border border-slate-200 p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">
            Outstanding{client.isParent && childClients.length > 0 ? ' (combined)' : ''}
          </p>
          <p className={`text-lg font-bold ${client.outstandingBalance > 0 ? 'text-amber-600' : 'text-green-600'}`}>
            ₹{formatAmount(client.outstandingBalance)}
          </p>
          {client.isParent && childClients.length > 0 && (
            <p className="text-xs text-slate-400 mt-0.5">parent + all sub-accounts</p>
          )}
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Credit Limit</p>
          {client.parentClientId ? (() => {
            // Child account — limit is governed by the parent, not by the child itself
            const parent = allClients.find(c => c.id === client.parentClientId)
            return (
              <>
                <p className="text-lg font-bold text-slate-700">
                  {parent && parent.creditLimit > 0
                    ? `₹${formatAmount(parent.creditLimit)}`
                    : 'Unlimited'}
                </p>
                <p className="text-xs text-blue-500 mt-0.5">shared with parent</p>
              </>
            )
          })() : (
            <p className="text-lg font-bold text-slate-700">
              {client.creditLimit > 0 ? `₹${formatAmount(client.creditLimit)}` : 'Unlimited'}
            </p>
          )}
        </div>
        <div
          className={`bg-white rounded-xl border p-4 ${isOwnerOrAdmin ? 'cursor-pointer hover:border-purple-300 hover:bg-purple-50/40 transition-colors' : 'border-slate-200'}`}
          onClick={isOwnerOrAdmin ? () => setShowInterestSettingsModal(true) : undefined}
          title={isOwnerOrAdmin ? 'Click to configure interest rate' : undefined}
        >
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Interest Rate</p>
          <p className={`text-lg font-bold ${client.monthlyInterestRate > 0 ? 'text-purple-700' : 'text-slate-300'}`}>
            {client.monthlyInterestRate > 0 ? `${client.monthlyInterestRate}%/mo` : '—'}
          </p>
          {isOwnerOrAdmin && (
            <p className="text-xs text-purple-400 mt-0.5">{client.monthlyInterestRate > 0 ? `${client.interestGraceDays}d grace` : 'Click to set'}</p>
          )}
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Transactions</p>
          <p className="text-lg font-bold text-slate-700">
            {isParent ? transactions.length : (ownTxPage?.totalElements ?? transactions.length)}
          </p>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4">
          <p className="text-xs text-slate-500 uppercase tracking-wide mb-1">Interest Recovered</p>
          <p className={`text-lg font-bold ${totalInterestRecovered > 0 ? 'text-purple-700' : 'text-slate-400'}`}>
            ₹{formatAmount(totalInterestRecovered)}
          </p>
          {client.isParent && childClients.length > 0 && (
            <p className="text-xs text-slate-400 mt-0.5">parent + all sub-accounts</p>
          )}
        </div>
      </div>

      {/* Transaction ledger */}
      <div className="px-6">
        {/* Filter toolbar */}
        <div className="flex flex-wrap items-center gap-3 mb-4">
          <h3 className="text-sm font-semibold text-slate-600 uppercase tracking-wide">Ledger History</h3>
          <div className="flex items-center gap-1 ml-auto bg-slate-100 rounded-lg p-0.5">
            {(['all', 'month', 'range'] as FilterMode[]).map(m => (
              <button
                key={m}
                onClick={() => setFilterMode(m)}
                className={`px-3 py-1.5 rounded-md text-xs font-medium transition-all ${
                  filterMode === m
                    ? 'bg-white text-slate-800 shadow-sm'
                    : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                {m === 'all' ? 'All' : m === 'month' ? 'By Month' : 'Date Range'}
              </button>
            ))}
          </div>
        </div>

        {/* Filter controls */}
        {filterMode === 'month' && (
          <div className="mb-4 flex flex-wrap gap-2">
            {availableMonths.length === 0 ? (
              <span className="text-xs text-slate-400">No transactions yet</span>
            ) : (
              availableMonths.map(m => (
                <button
                  key={m}
                  onClick={() => setSelectedMonth(prev => prev === m ? '' : m)}
                  className={`px-3 py-1.5 rounded-full text-xs font-medium border transition-all ${
                    selectedMonth === m
                      ? 'bg-blue-600 border-blue-600 text-white'
                      : 'bg-white border-slate-200 text-slate-600 hover:border-blue-300'
                  }`}
                >
                  {m}
                </button>
              ))
            )}
          </div>
        )}

        {filterMode === 'range' && (
          <div className="mb-4 flex items-center gap-3">
            <div className="flex items-center gap-2">
              <label className="text-xs text-slate-500 whitespace-nowrap">From</label>
              <input type="date" value={fromDate} onChange={e => setFromDate(e.target.value)}
                className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
            </div>
            <div className="flex items-center gap-2">
              <label className="text-xs text-slate-500 whitespace-nowrap">To</label>
              <input type="date" value={toDate} onChange={e => setToDate(e.target.value)}
                className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
            </div>
            {(fromDate || toDate) && (
              <button onClick={() => { setFromDate(''); setToDate('') }}
                className="text-xs text-slate-400 hover:text-slate-600">Clear</button>
            )}
          </div>
        )}

        {isLoading ? (
          <div className="text-sm text-slate-400 py-8 text-center">Loading transactions…</div>
        ) : client.isParent ? (
          /* ── Parent view: parent's own transactions first, then sub-accounts ── */
          <div className="space-y-8">
            {/* Parent's own transactions — expanded by default */}
            <div>
              <button
                type="button"
                onClick={() => toggleSection('own')}
                className="w-full flex items-center justify-between mb-3 pb-2 border-b border-slate-100 hover:bg-slate-50/50 rounded px-1 -mx-1 transition-colors"
              >
                <div className="flex items-center gap-2.5">
                  <span className="w-8 h-8 rounded-full bg-slate-200 text-slate-600 text-xs font-bold flex items-center justify-center">
                    {client.name.charAt(0).toUpperCase()}
                  </span>
                  <div className="text-left">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-slate-700">{client.name}</span>
                      <span className="text-xs bg-slate-100 text-slate-500 px-1.5 py-0.5 rounded font-medium">own transactions</span>
                    </div>
                    <p className="text-xs text-slate-400">{client.phone}</p>
                  </div>
                </div>
                <svg
                  className={`w-4 h-4 text-slate-400 transition-transform ${expandedSections.has('own') ? 'rotate-180' : ''}`}
                  fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                </svg>
              </button>
              {expandedSections.has('own') && (
                filteredOwn.length === 0 ? (
                  <div className="text-sm text-slate-400 py-4 text-center bg-white rounded-xl border border-slate-200">
                    {ownTransactions.length === 0 ? 'No own transactions yet.' : 'No transactions match this filter.'}
                  </div>
                ) : (
                  <div className="space-y-3">
                    {renderWeekGroups(buildWeekGroups(filteredOwn as CreditTransaction[]), clientId)}
                  </div>
                )
              )}
            </div>

            {/* Sub-account sections — collapsed by default */}
            {childClients.map((child, i) => {
              const sectionKey       = String(child.id)
              const isExpanded       = expandedSections.has(sectionKey)
              const childFiltered    = childFilteredTxs[i]
              const childGroups      = buildWeekGroups(childFiltered)
              const childLedger      = allClients.find(c => c.id === child.id)
              const childOutstanding = childLedger?.outstandingBalance ?? 0
              return (
                <div key={child.id}>
                  <button
                    type="button"
                    onClick={() => toggleSection(sectionKey)}
                    className="w-full flex items-center justify-between mb-3 pb-2 border-b border-slate-100 hover:bg-slate-50/50 rounded px-1 -mx-1 transition-colors"
                  >
                    <div className="flex items-center gap-2.5">
                      <span className="w-8 h-8 rounded-full bg-blue-100 text-blue-600 text-xs font-bold flex items-center justify-center">
                        {child.name.charAt(0).toUpperCase()}
                      </span>
                      <div className="text-left">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-semibold text-slate-700">{child.name}</span>
                          <span className="text-xs bg-blue-100 text-blue-600 px-1.5 py-0.5 rounded font-medium">sub-account</span>
                        </div>
                        <p className="text-xs text-slate-400">{child.phone}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-4">
                      <div className="text-right">
                        <p className="text-xs text-slate-400">Outstanding</p>
                        <p className={`text-sm font-bold ${childOutstanding > 0 ? 'text-amber-600' : 'text-green-600'}`}>
                          ₹{formatAmount(childOutstanding)}
                        </p>
                      </div>
                      <svg
                        className={`w-4 h-4 text-slate-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
                        fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                      </svg>
                    </div>
                  </button>
                  {isExpanded && (
                    childTxQueries[i]?.isLoading ? (
                      <div className="text-sm text-slate-400 py-4 text-center">Loading…</div>
                    ) : childFiltered.length === 0 ? (
                      <div className="text-sm text-slate-400 py-4 text-center bg-white rounded-xl border border-slate-200">
                        {(childTxQueries[i]?.data?.content ?? []).length === 0 ? 'No transactions yet.' : 'No transactions match this filter.'}
                      </div>
                    ) : (
                      <div className="space-y-3">{renderWeekGroups(childGroups, child.id)}</div>
                    )
                  )}
                </div>
              )
            })}
          </div>
        ) : filtered.length === 0 ? (
          <div className="text-sm text-slate-400 py-8 text-center bg-white rounded-xl border border-slate-200">
            {transactions.length === 0 ? 'No transactions yet.' : 'No transactions match this filter.'}
          </div>
        ) : (
          <>
            <div className="space-y-4">{renderWeekGroups(weekGroups, clientId)}</div>
            {/* Pagination only for non-parent single-client view and when no filter is active */}
            {!isParent && filterMode === 'all' && ownTxPage && (
              <Pagination
                data={ownTxPage}
                onPageChange={p => { setTxPage(p); qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId, clientId] }) }}
                onPageSizeChange={s => { setTxPageSize(s); setTxPage(0) }}
              />
            )}
          </>
        )}
      </div>
    </div>
  )
}

// ─── Main Credit Page ────────────────────────────────────────────────────────

export default function CreditPage() {
  const { user } = useAuthStore()
  const isOwnerOrAdmin = user?.role === 'OWNER' || user?.role === 'ADMIN'

  const { selectedPumpId } = usePumpStore()
  const [selectedClient, setSelectedClient] = useState<CreditClient | null>(null)
  const [showLimitModal, setShowLimitModal] = useState<CreditClient | null>(null)
  const [showInterestModal, setShowInterestModal] = useState<CreditClient | null>(null)
  // Search query for filtering clients in the main list
  const [searchQuery, setSearchQuery] = useState('')

  // Only one parent can be expanded at a time; null = all collapsed
  const [expandedParentId, setExpandedParentId] = useState<number | null>(null)
  const toggleParent = (id: number) =>
    setExpandedParentId(prev => prev === id ? null : id)




  const pumpId = selectedPumpId

  const { data: clients = [], isLoading, error } = useQuery({
    queryKey: ['credit-ledger', pumpId],
    queryFn: () => creditApi.getLedgerSummary(pumpId!),
    enabled: !!pumpId,
  })

  // Fetch the full client list for reliable parentClientId / isParent structure.
  // The ledger endpoint may not populate parentClientId, so we use this for grouping.
  const { data: allCreditClients = [] } = useQuery({
    queryKey: ['creditClients', pumpId],
    queryFn:  () => pumpApi.getCreditClients(pumpId!),
    enabled:  !!pumpId,
  })

  const formatAmount = (n: number) =>
    n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  if (!isOwnerOrAdmin && !pumpId) {
    return (
      <div className="p-6 w-full lg:max-w-[85%] 2xl:max-w-[60%]">
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-amber-700 text-sm">
          You are not assigned to any pump. Ask the Owner or Admin to assign you.
        </div>
      </div>
    )
  }

  if (selectedClient && pumpId) {
    return (
      <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">
        <ClientDetail
          clientId={selectedClient.id}
          pumpId={pumpId}
          isOwnerOrAdmin={isOwnerOrAdmin}
          onBack={() => setSelectedClient(null)}
        />
      </div>
    )
  }

  const totalOutstanding = clients.reduce((sum, c) => sum + c.outstandingBalance, 0)

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">
      {showLimitModal && pumpId && (
        <SetCreditLimitModal
          client={showLimitModal}
          pumpId={pumpId}
          onClose={() => setShowLimitModal(null)}
        />
      )}
      {showInterestModal && pumpId && (
        <SetInterestSettingsModal
          client={showInterestModal}
          pumpId={pumpId}
          onClose={() => setShowInterestModal(null)}
        />
      )}

      {/* Page title */}
      <div>
        <h2 className="text-xl font-bold text-slate-800">Credit</h2>
        <p className="text-slate-500 text-sm mt-1">Manage credit clients, outstanding balances, payments, and interest.</p>
      </div>

      {/* Page header */}
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Credit Ledger</h1>
          <p className="text-sm text-slate-500 mt-0.5">Track outstanding balances and record payments</p>
        </div>
        <div className="flex items-center gap-3 flex-wrap">
          {totalOutstanding > 0 && (
            <div className="text-right">
              <p className="text-xs text-slate-500">Total Outstanding</p>
              <p className="text-xl font-bold text-amber-600">₹{formatAmount(totalOutstanding)}</p>
            </div>
          )}
        </div>
      </div>

      {/* Search bar */}
      <div className="relative">
        <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
        </svg>
        <input
          type="text"
          value={searchQuery}
          onChange={e => setSearchQuery(e.target.value)}
          placeholder="Search by client name or phone…"
          className="w-full pl-9 pr-9 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-400 bg-white"
        />
        {searchQuery && (
          <button
            onClick={() => setSearchQuery('')}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        )}
      </div>

      {isLoading && (
        <div className="text-sm text-slate-400 py-12 text-center">Loading clients…</div>
      )}

      {error && (
        <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-xl px-4 py-3">
          Failed to load credit data. Please try again.
        </div>
      )}

      {!isLoading && clients.length === 0 && (
        <div className="text-sm text-slate-400 bg-white rounded-xl border border-slate-200 py-12 text-center">
          No credit clients registered yet. Add clients in the Setup page.
        </div>
      )}

      {!isLoading && clients.length > 0 && (() => {
        // Use allCreditClients (full list) for reliable parentClientId / isParent structure.
        // clients (ledger) has balances; allCreditClients has the hierarchy.
        const structureMap = new Map(allCreditClients.map(c => [c.id, c]))

        // A client is a child if allCreditClients says so — never rely on ledger's parentClientId
        const isChildId = (id: number) => !!structureMap.get(id)?.parentClientId

        // Build a map: parentId → ledger entries for each child
        const childMap = new Map<number, typeof clients>()
        allCreditClients.forEach(sc => {
          if (!sc.parentClientId) return
          const ledgerEntry = clients.find(c => c.id === sc.id)
          if (ledgerEntry) {
            const arr = childMap.get(sc.parentClientId) ?? []
            arr.push(ledgerEntry)
            childMap.set(sc.parentClientId, arr)
          }
        })

        // Root clients = those that are NOT children in the full structure, filtered by search.
        // A parent matches if its own name/phone matches OR any of its sub-accounts match.
        const q = searchQuery.trim().toLowerCase()
        const rootClients = clients.filter(c => {
          if (isChildId(c.id)) return false
          if (!q) return true
          const matchesSelf = c.name.toLowerCase().includes(q) || (c.phone ?? '').includes(q)
          const children = childMap.get(c.id) ?? []
          const matchesChild = children.some(ch => ch.name.toLowerCase().includes(q) || (ch.phone ?? '').includes(q))
          return matchesSelf || matchesChild
        })

        const renderClientCard = (client: typeof clients[0], isChild = false) => {
          // Use the reliable structure map to determine child status — not the isChild param alone
          const isActualChild = isChild || isChildId(client.id)
          const limitPct = client.creditLimit > 0
            ? Math.min(100, (client.outstandingBalance / client.creditLimit) * 100)
            : null
          const isNearLimit  = limitPct !== null && limitPct >= 80
          const children     = childMap.get(client.id) ?? []
          const hasChildren  = !isActualChild && children.length > 0
          const isExpanded   = expandedParentId === client.id

          return (
            <div
              key={client.id}
              onClick={hasChildren ? () => toggleParent(client.id) : undefined}
              className={`bg-white rounded-xl border border-slate-200 px-5 py-4 flex items-center gap-4 transition-all ${
                isActualChild ? 'ml-8 border-l-4 border-l-blue-200 hover:border-blue-300 hover:shadow-sm' :
                hasChildren   ? 'cursor-pointer hover:border-blue-400 hover:shadow-sm select-none' :
                                'hover:border-blue-300 hover:shadow-sm'
              }`}
            >
              {/* Avatar */}
              <div className={`w-10 h-10 rounded-full font-bold flex items-center justify-center flex-shrink-0 text-sm ${
                isActualChild ? 'bg-blue-50 text-blue-500' : 'bg-blue-100 text-blue-700'
              }`}>
                {client.name.charAt(0).toUpperCase()}
              </div>

              {/* Name + phone */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className="text-sm font-semibold text-slate-800 truncate">{client.name}</p>
                  {isActualChild && (
                    <span className="text-xs bg-blue-50 text-blue-500 border border-blue-200 px-1.5 py-0.5 rounded font-medium">
                      sub-account
                    </span>
                  )}
                  {hasChildren && (
                    <span className="text-xs bg-slate-100 text-slate-500 px-1.5 py-0.5 rounded font-medium">
                      {children.length} sub-account{children.length !== 1 ? 's' : ''}
                    </span>
                  )}
                </div>
                <p className="text-xs text-slate-400">{client.phone}</p>
                {/* Credit usage bar — only for root accounts with their own limit */}
                {!isActualChild && client.creditLimit > 0 && (
                  <div className="mt-1.5">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 bg-slate-100 rounded-full h-1.5 overflow-hidden">
                        <div
                          className={`h-full rounded-full transition-all ${isNearLimit ? 'bg-red-500' : 'bg-blue-400'}`}
                          style={{ width: `${limitPct}%` }}
                        />
                      </div>
                      <span className={`text-xs ${isNearLimit ? 'text-red-600 font-medium' : 'text-slate-400'}`}>
                        {limitPct?.toFixed(0)}% of limit
                      </span>
                    </div>
                  </div>
                )}
              </div>

              {/* Credit limit + interest rate */}
              <div className="text-right flex-shrink-0 hidden sm:block space-y-0.5">
                <div>
                  <p className="text-xs text-slate-400 mb-0.5">Credit Limit</p>
                  {isActualChild ? (
                    <p className="text-sm font-medium text-blue-500">Shared with parent</p>
                  ) : (
                    <p className="text-sm font-medium text-slate-600">
                      {client.creditLimit > 0 ? `₹${formatAmount(client.creditLimit)}` : 'Unlimited'}
                    </p>
                  )}
                </div>
                {client.monthlyInterestRate > 0 && (
                  <p className="text-xs text-purple-600 font-medium">{client.monthlyInterestRate}%/mo interest</p>
                )}
              </div>

              {/* Outstanding */}
              <div className="text-right flex-shrink-0 w-28">
                <p className="text-xs text-slate-400 mb-0.5">Outstanding</p>
                <p className={`text-base font-bold ${client.outstandingBalance > 0 ? 'text-amber-600' : 'text-green-600'}`}>
                  ₹{formatAmount(client.outstandingBalance)}
                </p>
              </div>

              {/* Actions — stopPropagation so buttons don't trigger the parent expand/collapse */}
              <div className="flex items-center gap-2 flex-shrink-0" onClick={e => e.stopPropagation()}>
                {/* Edit credit limit — only for root accounts; children inherit parent's limit */}
                {isOwnerOrAdmin && !isActualChild && (
                  <button
                    onClick={() => setShowLimitModal(client)}
                    title="Set credit limit"
                    className="p-2 text-slate-400 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                    </svg>
                  </button>
                )}
                <button
                  onClick={() => setSelectedClient(client)}
                  className="px-3 py-1.5 text-xs font-medium text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
                >
                  View Ledger
                </button>
              </div>

              {/* Chevron outside stopPropagation — clicking it also triggers the card's onClick */}
              {hasChildren && (
                <svg
                  className={`w-4 h-4 text-slate-400 transition-transform flex-shrink-0 ${isExpanded ? 'rotate-180' : ''}`}
                  fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
                </svg>
              )}
            </div>
          )
        }

        if (rootClients.length === 0 && q) {
          return (
            <div className="text-sm text-slate-400 bg-white rounded-xl border border-slate-200 py-10 text-center">
              No clients match "<span className="font-medium text-slate-600">{searchQuery.trim()}</span>"
            </div>
          )
        }

        return (
          <div className="space-y-3">
            {rootClients.map(root => {
              const children   = childMap.get(root.id) ?? []
              const isExpanded = expandedParentId === root.id
              return (
                <div key={root.id} className="space-y-2">
                  {renderClientCard(root)}
                  {children.length > 0 && isExpanded && children.map(child => renderClientCard(child, true))}
                </div>
              )
            })}
          </div>
        )
      })()}
    </div>
  )
}
