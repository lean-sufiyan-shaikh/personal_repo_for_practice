import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { shiftApi } from '../../../api/shiftApi'
import { pumpApi } from '../../../api/pumpApi'
import type { Shift, CreditEntryInput } from '../../../types/shift'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { parseApiError } from '../../../utils/apiError'
import { Modal } from '../../../components/Modal'
import { FUEL_LABELS } from '../../../constants/fuel'
import { CreditClientSearch } from '../components/CreditClientSearch'
import { useAuthStore } from '../../../store/authStore'
import { canPerformAction } from '../../../permissions/permissions'
import { focusOnMount } from '../../../utils/focus'
import { NumericInput } from '../../../components/NumericInput'
import { useToastStore } from '../../../store/toastStore'
import { readDraft, removeDraft, useSaveDraft, writeDraft } from '../../../hooks/useSaveDraft'
import { DraftRestoredNotice } from '../../../components/DraftRestoredNotice'
import { LoadingButton } from '../../../components/LoadingButton'
import { attemptFor } from '../../../utils/idempotencyKey'
import { formatCurrency } from '../../../utils/format'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'

interface CreditEntryDraft {
  clientSearch: string
  clientName: string
  clientId: number | null
  subAccountId: number | null
  fuelType: string
  amount: string
  liters: string
  inputMode: 'amount' | 'liters'
  billNo: string
  description: string
  submitAttempt: SubmitAttempt | null
}

// ── Add Credit Entry Modal (mid-shift) ────────────────────────────────────────


export function AddCreditEntryModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const role = useAuthStore((state) => state.user?.role)
  const canVoidEntry = canPerformAction('shift:void-credit-entry', role)
  const { data: creditClients = [] } = useQuery({
    queryKey: ['creditClients', shift.pumpId],
    queryFn: () => pumpApi.getCreditClients(shift.pumpId),
  })

  const draftName = `credit-entry:${shift.id}`
  const defaultFuelType = shift.fuelReadings[0]?.fuelType ?? ''
  const [restoredDraft, setRestoredDraft] = useState(() => readDraft<CreditEntryDraft>(draftName))
  const draft = restoredDraft?.values
  const [clientSearch,   setClientSearch]   = useState(() => draft?.clientSearch ?? '')
  const [clientName,     setClientName]     = useState(() => draft?.clientName ?? '')
  const [clientId,       setClientId]       = useState<number | null>(() => draft?.clientId ?? null)
  const [subAccountId,   setSubAccountId]   = useState<number | null>(() => draft?.subAccountId ?? null)
  const [showDropdown,   setShowDropdown]   = useState(false)
  const [fuelType,       setFuelType]       = useState<string>(() => draft?.fuelType ?? defaultFuelType)
  const [amount,         setAmount]         = useState(() => draft?.amount ?? '')
  const [liters,         setLiters]         = useState(() => draft?.liters ?? '')
  const [inputMode,      setInputMode]      = useState<'amount' | 'liters'>(() => draft?.inputMode ?? 'amount')
  const [billNo,         setBillNo]         = useState(() => draft?.billNo ?? '')
  const [description,    setDescription]    = useState(() => draft?.description ?? '')
  const [submitAttempt,  setSubmitAttempt]  = useState<SubmitAttempt | null>(() => draft?.submitAttempt ?? null)
  const [error,          setError]          = useState<string | null>(null)

  const draftValues: CreditEntryDraft = {
    clientSearch, clientName, clientId, subAccountId, fuelType, amount, liters, inputMode, billNo, description, submitAttempt,
  }
  const hasEntries = clientSearch.trim() !== '' || amount !== '' || liters !== '' || billNo.trim() !== '' || description.trim() !== ''
  useSaveDraft(draftName, draftValues, hasEntries)

  const discardRestoredEntries = () => {
    removeDraft(draftName)
    setRestoredDraft(null)
    setClientSearch('')
    setClientName('')
    setClientId(null)
    setSubAccountId(null)
    setFuelType(defaultFuelType)
    setAmount('')
    setLiters('')
    setInputMode('amount')
    setBillNo('')
    setDescription('')
    setSubmitAttempt(null)
    setError(null)
  }

  const priceForFuelType = (ft: string): number | null =>
    shift.fuelReadings.find((r) => r.fuelType === ft)?.priceSnapshot ?? null

  // Void credit entry state
  const [voidTargetId,  setVoidTargetId]   = useState<number | null>(null)
  const [voidReason,    setVoidReason]     = useState('')
  const [voidError,     setVoidError]      = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: ({ entry, idempotencyKey }: { entry: CreditEntryInput; idempotencyKey: string }) =>
      shiftApi.addCreditEntry(shift.pumpId, shift.id, entry, idempotencyKey),
    onSuccess: (_shift, { entry }) => {
      removeDraft(draftName)
      qc.invalidateQueries({ queryKey: ['activeShifts', shift.pumpId] })
      addToast(`Credit of ₹${entry.amount.toLocaleString('en-IN', { minimumFractionDigits: 2 })} added for ${entry.clientName}`, 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, 'Failed to save credit entry. Please try again.')),
  })

  const voidMutation = useMutation({
    mutationFn: ({ entryId, reason }: { entryId: number; reason: string }) =>
      shiftApi.voidCreditEntry(shift.pumpId, shift.id, entryId, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['activeShifts', shift.pumpId] })
      setVoidTargetId(null); setVoidReason(''); setVoidError(null)
    },
    onError: (err: unknown) => setVoidError(parseApiError(err, 'Failed to delete entry. Please try again.')),
  })

  const selectedParent = creditClients.find(c => c.id === clientId)
  const subAccounts = selectedParent?.isParent
    ? creditClients.filter(c => c.parentClientId === clientId)
    : []

  const canSave =
    clientId !== null &&
    fuelType.length > 0 &&
    parseFloat(amount) > 0

  const handleSubmit = () => {
    const amt = parseFloat(amount)
    if (!clientId)           { setError('Please select a client from the list.'); return }
    if (!fuelType)           { setError('Fuel type is required'); return }
    if (!amount || isNaN(amt) || amt <= 0) { setError('Enter a valid amount'); return }
    setError(null)
    const entry: CreditEntryInput = {
      clientId:    subAccountId ?? clientId,
      clientName: clientName.trim(),
      fuelType,
      amount: amt,
      billNo:      billNo.trim() || undefined,
      description: description.trim() || undefined,
    }
    // Saved before sending, so a resend after a timeout (even after a reload) reuses the key.
    const attempt = attemptFor(submitAttempt, entry)
    setSubmitAttempt(attempt)
    writeDraft(draftName, { ...draftValues, submitAttempt: attempt })
    mutation.mutate({ entry, idempotencyKey: attempt.key })
  }

  return (
    <Modal
      title="Add Credit Entry"
      subtitle={`Nozzle ${shift.nozzles.map((n) => `#${n.nozzleNumber}`).join(', ')} · ${shift.operatorName}`}
      tone="warning"
      size="md"
      onClose={onClose}
      bodyClassName="space-y-3"
      footer={
        <>
          <button onClick={onClose}
            className="ui-btn ui-btn-secondary flex-1">
            Cancel
          </button>
          <LoadingButton type="button" onClick={handleSubmit} disabled={!canSave} isLoading={mutation.isPending}
            loadingLabel="Saving the credit entry"
            className="ui-btn-primary flex-1">
            Save Entry
          </LoadingButton>
        </>
      }
    >
          {restoredDraft && (
            <DraftRestoredNotice savedAt={restoredDraft.savedAt} onDiscard={discardRestoredEntries} />
          )}

          {/* Existing credit entries with void option */}
          {shift.creditEntries.length > 0 && (
            <div className="ui-card p-0 overflow-hidden">
              <div className="ui-toolbar px-3 py-2">
                <p className="ui-toolbar-title text-xs">Recorded entries</p>
                {canVoidEntry && <span className="ui-toolbar-note">Tap Void to cancel one</span>}
              </div>
              <div className="divide-y divide-border max-h-40 overflow-y-auto">
                {shift.creditEntries.map((entry) => (
                  <div key={entry.id} className={`flex items-center gap-2 px-3 py-2 ${entry.voidStatus === 'VOIDED' ? 'opacity-40' : ''}`}>
                    <div className="flex-1 min-w-0">
                      <p className="text-xs font-medium text-ink truncate">{entry.clientName}</p>
                      <p className="text-xs text-ink-3">
                        {formatCurrency(entry.amount)}
                        {entry.fuelType && ` · ${entry.fuelType}`}
                        {entry.voidStatus === 'VOIDED' && ' · VOIDED'}
                      </p>
                    </div>
                    {canVoidEntry && entry.voidStatus !== 'VOIDED' && voidTargetId !== entry.id && (
                      <button
                        onClick={() => { setVoidTargetId(entry.id); setVoidReason(''); setVoidError(null) }}
                        className="ui-btn ui-btn-ghost min-h-0 shrink-0 border border-bad px-2 py-0.5 text-xs text-bad hover:text-bad-strong"
                      >
                        Void
                      </button>
                    )}
                  </div>
                ))}
              </div>
              {/* Void reason input */}
              {voidTargetId !== null && (
                <div className="border-t border-warn bg-warn-bg px-3 py-2">
                  <div className="ui-inline-form space-y-2 border-warn bg-surface">
                  <input aria-label="Reason for voiding"
                    ref={focusOnMount}
                    type="text"
                    value={voidReason}
                    onChange={(e) => setVoidReason(e.target.value)}
                    placeholder="Reason for voiding (required)"
                    className="ui-input-compact text-xs"
                  />
                  {voidError && <p role="alert" className="ui-error-text">{voidError}</p>}
                  <div className="flex gap-2">
                    <button onClick={() => { setVoidTargetId(null); setVoidReason('') }}
                      className="ui-btn ui-btn-secondary min-h-0 flex-1 px-3 py-1 text-xs">
                      Cancel
                    </button>
                    <button
                      onClick={() => {
                        if (!voidReason.trim()) { setVoidError('Reason is required'); return }
                        voidMutation.mutate({ entryId: voidTargetId!, reason: voidReason.trim() })
                      }}
                      disabled={voidMutation.isPending}
                      className="ui-btn ui-btn-danger min-h-0 flex-1 px-3 py-1 text-xs disabled:opacity-50">
                      {voidMutation.isPending ? '…' : 'Confirm Void'}
                    </button>
                  </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Client — root/parent accounts only */}
          <CreditClientSearch
            search={clientSearch}
            creditClients={creditClients}
            showDropdown={showDropdown}
            onSearchChange={(text) => {
              setClientSearch(text)
              setClientName(text)
              setClientId(null)
              setSubAccountId(null)
              setShowDropdown(true)
            }}
            onShowDropdownChange={setShowDropdown}
            onSelect={(c) => {
              setClientSearch(c.name)
              setClientName(c.name)
              setClientId(c.id)
              setSubAccountId(null)
              setShowDropdown(false)
            }}
            inputClassName="text-sm"
            placeholder="Search client name…"
          />

          {/* Sub-account picker — shown when selected parent has children */}
          {subAccounts.length > 0 && (
            <div>
              <span className="ui-label">
                Sub-account <span className="text-ink-3 font-normal">(optional)</span>
              </span>
              <SearchableSelect label="Sub-account"
                value={subAccountId != null ? subAccountId.toString() : ''}
                onChange={v => setSubAccountId(v === '' ? null : Number(v))}
                placeholder={`— Bill to parent (${clientName}) —`}
                accentColor="orange"
                options={subAccounts.map(s => ({ value: s.id.toString(), label: s.name }))}
              />
            </div>
          )}

          {/* Fuel + Amount/Liters */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className="ui-label">
                Fuel <span className="text-bad">*</span>
              </span>
              <SearchableSelect label="Fuel"
                value={fuelType}
                onChange={v => {
                  setFuelType(v)
                  if (inputMode === 'liters') { setAmount(''); setLiters('') }
                }}
                accentColor="orange"
                options={shift.fuelReadings.map(r => ({
                  value: r.fuelType,
                  label: FUEL_LABELS[r.fuelType] ?? r.fuelType,
                }))}
              />
            </div>
            <div>
              <div className="flex items-center justify-between mb-1">
                <span className="ui-label mb-0">
                  {inputMode === 'amount' ? 'Amount (₹)' : 'Liters'}{' '}
                  <span className="text-bad">*</span>
                </span>
                <div className="flex gap-1 text-xs">
                  <button type="button"
                    onClick={() => { setInputMode('amount'); setLiters('') }}
                    aria-pressed={inputMode === 'amount'}
                    aria-label="Enter amount in rupees"
                    className={`min-h-11 min-w-11 rounded-control border border-border px-2 font-medium transition-colors ${inputMode === 'amount' ? 'bg-warn text-on-primary' : 'bg-surface text-ink-3 hover:bg-bg'}`}>
                    ₹
                  </button>
                  <button type="button"
                    onClick={() => {
                      const price = priceForFuelType(fuelType)
                      const initLiters = price && amount ? (parseFloat(amount) / price).toFixed(2) : ''
                      setInputMode('liters')
                      setLiters(initLiters)
                    }}
                    disabled={!priceForFuelType(fuelType)}
                    aria-pressed={inputMode === 'liters'}
                    aria-label="Enter litres"
                    className={`min-h-11 min-w-11 rounded-control border border-border px-2 font-medium transition-colors ${inputMode === 'liters' ? 'bg-warn text-on-primary' : 'bg-surface text-ink-3 hover:bg-bg'} disabled:opacity-40 disabled:cursor-not-allowed`}>
                    L
                  </button>
                </div>
              </div>
              {inputMode === 'amount' ? (
                <>
                  <NumericInput aria-label="Credit amount in rupees" step="0.01" min="0.01" value={amount}
                    onChange={(e) => setAmount(e.target.value)}
                    placeholder="0.00" />
                  {(() => {
                    const price = priceForFuelType(fuelType)
                    const unit = fuelType === 'CNG' ? 'kg' : 'L'
                    const litersCalc = price && amount ? (parseFloat(amount) / price) : null
                    return litersCalc ? (
                      <p className="text-xs text-ink-3 mt-1">
                        {litersCalc.toFixed(2)} {unit} × ₹{price} = {formatCurrency(parseFloat(amount))}
                      </p>
                    ) : null
                  })()}
                </>
              ) : (
                <>
                  <NumericInput aria-label="Credit quantity" step="0.001" min="0.001" value={liters}
                    onChange={(e) => {
                      const price = priceForFuelType(fuelType)
                      const amt = price && e.target.value ? (parseFloat(e.target.value) * price).toFixed(2) : ''
                      setLiters(e.target.value)
                      setAmount(amt)
                    }}
                    placeholder="0.00" />
                  {liters && amount && (() => {
                    const price = priceForFuelType(fuelType)
                    const unit = fuelType === 'CNG' ? 'kg' : 'L'
                    return price ? (
                      <p className="text-xs text-ok mt-1 font-medium">
                        {parseFloat(liters).toFixed(2)} {unit} × ₹{price} = {formatCurrency(parseFloat(amount))}
                      </p>
                    ) : null
                  })()}
                </>
              )}
            </div>
          </div>

          {/* Bill No + Description */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block">
                <span className="ui-label">Bill No</span>
                <input type="text" value={billNo} onChange={(e) => setBillNo(e.target.value)}
                  placeholder="e.g. B-101"
                  className="text-sm" />
              </label>
            </div>
            <div>
              <label className="block">
                <span className="ui-label">Description</span>
                <input type="text" value={description} onChange={(e) => setDescription(e.target.value)}
                  placeholder="e.g. given to driver"
                  className="text-sm" />
              </label>
            </div>
          </div>

          {error && (
            <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
          )}
    </Modal>
  )
}
