import { useState, useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { shiftApi } from '../../api/shiftApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import { userApi } from '../../api/userApi'
import type { BackfillShiftRequest, BackfillNozzleReading, CreditEntryInput } from '../../types/shift'
import type { DUOption, NozzleDetail } from '../../types/shift'
import { ModalPortal } from '../../components/ModalPortal'
import { parseApiError } from '../../utils/apiError'

// ── Helpers ───────────────────────────────────────────────────────────────────

function localDateStr(d: Date): string {
  const y   = d.getFullYear()
  const m   = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

// Max backfill date = yesterday. Min = 365 days ago.
function maxDate(): string {
  return localDateStr(new Date(Date.now() - 86_400_000))
}
function minDate(): string {
  return localDateStr(new Date(Date.now() - 365 * 86_400_000))
}

function safeParse(v: string): number {
  const n = parseFloat(v)
  return isFinite(n) && n >= 0 ? n : 0
}

const FUEL_LABELS: Record<string, string> = {
  PETROL: 'Petrol', SPEED_PETROL: 'Speed Petrol',
  DIESEL: 'Diesel', SPEED_DIESEL: 'Speed Diesel', CNG: 'CNG',
}

// ── Credit entry row state ────────────────────────────────────────────────────

interface CreditRow {
  _key: number
  clientName: string
  amount: string
  fuelType: string
  billNo: string
  description: string
}

let _rowKey = 0
function newCreditRow(): CreditRow {
  return { _key: ++_rowKey, clientName: '', amount: '', fuelType: '', billNo: '', description: '' }
}

// ── Props ─────────────────────────────────────────────────────────────────────

interface Props {
  pumpId: number
  onClose: () => void
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function BackfillShiftModal({ pumpId, onClose }: Props) {
  const qc = useQueryClient()

  // ── Section 1: Shift Identity ─────────────────────────────────────────────
  const [shiftDate,         setShiftDate]         = useState(maxDate())
  const [shiftDefinitionId, setShiftDefinitionId] = useState<number | null>(null)
  const [selectedDU,        setSelectedDU]        = useState<DUOption | null>(null)

  // ── Section 2: Staff & Nozzles ────────────────────────────────────────────
  const [operatorId,        setOperatorId]        = useState<number | null>(null)
  const [operatorSearch,    setOperatorSearch]    = useState('')
  const [selectedNozzleIds, setSelectedNozzleIds] = useState<Set<number>>(new Set())

  // ── Section 3: Meter Readings (per nozzle) ────────────────────────────────
  const [openingReadings, setOpeningReadings] = useState<Record<number, string>>({})
  const [closingReadings, setClosingReadings] = useState<Record<number, string>>({})

  // ── Section 4: Payment ────────────────────────────────────────────────────
  const [cashCollected,      setCashCollected]      = useState('')
  const [upiCollected,       setUpiCollected]       = useState('')
  const [cardCollected,      setCardCollected]      = useState('')
  const [fleetCardCollected, setFleetCardCollected] = useState('')

  // ── Section 5: Credit Sales ───────────────────────────────────────────────
  const [creditTotal,      setCreditTotal]      = useState('')
  const [creditRows,       setCreditRows]       = useState<CreditRow[]>([])
  const [expandedCreditId, setExpandedCreditId] = useState<number | null>(null)

  // ── Misc ──────────────────────────────────────────────────────────────────
  const [discrepancyReason, setDiscrepancyReason] = useState('')
  const [error,             setError]             = useState<string | null>(null)

  // ── Data queries ──────────────────────────────────────────────────────────

  // Shift definitions effective on the selected date
  const { data: definitions = [], isLoading: defsLoading } = useQuery({
    queryKey: ['shift-definitions-for-date', pumpId, shiftDate],
    queryFn:  () => shiftDefinitionApi.getForDate(pumpId, shiftDate),
    enabled:  !!shiftDate,
  })

  // Auto-select definition when list loads / changes
  useEffect(() => {
    setShiftDefinitionId(definitions.length === 1 ? definitions[0].id : null)
  }, [definitions])

  const { data: dus = [], isLoading: dusLoading } = useQuery({
    queryKey: ['dus', pumpId],
    queryFn:  () => shiftApi.getDUs(pumpId),
  })

  const { data: operators = [], isLoading: operatorsLoading } = useQuery({
    queryKey: ['operators', pumpId],
    queryFn:  () => userApi.getOperators(pumpId),
  })

  // Reset nozzle selections when DU changes
  useEffect(() => {
    setSelectedNozzleIds(new Set())
    setOpeningReadings({})
    setClosingReadings({})
  }, [selectedDU])

  // ── Filtered operator list ────────────────────────────────────────────────
  const filteredOperators = operators.filter((op) =>
    op.status === 'ACTIVE' &&
    (operatorSearch === '' ||
      op.fullName.toLowerCase().includes(operatorSearch.toLowerCase()) ||
      op.employeeId.toLowerCase().includes(operatorSearch.toLowerCase()))
  )

  // ── Nozzles for selected DU ───────────────────────────────────────────────
  const availableNozzles: NozzleDetail[] = selectedDU?.nozzles ?? []

  const toggleNozzle = (id: number) => {
    setSelectedNozzleIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
        setOpeningReadings((r) => { const n = { ...r }; delete n[id]; return n })
        setClosingReadings((r) => { const n = { ...r }; delete n[id]; return n })
      } else {
        next.add(id)
      }
      return next
    })
  }

  // ── Liters sold display per nozzle ────────────────────────────────────────
  function litersForNozzle(nozzleId: number): number | null {
    const o = parseFloat(openingReadings[nozzleId] ?? '')
    const c = parseFloat(closingReadings[nozzleId] ?? '')
    if (!isFinite(o) || !isFinite(c)) return null
    const diff = c - o
    return diff >= 0 ? diff : null
  }

  // ── Credit entry helpers ──────────────────────────────────────────────────

  function addCreditRow() {
    const row = newCreditRow()
    setCreditRows((prev) => [...prev, row])
    setExpandedCreditId(row._key)
  }

  function removeCreditRow(key: number) {
    setCreditRows((prev) => prev.filter((r) => r._key !== key))
    if (expandedCreditId === key) setExpandedCreditId(null)
  }

  function updateCreditRow(key: number, patch: Partial<CreditRow>) {
    setCreditRows((prev) => prev.map((r) => r._key === key ? { ...r, ...patch } : r))
  }

  // ── Submit ────────────────────────────────────────────────────────────────

  const mutation = useMutation({
    mutationFn: (req: BackfillShiftRequest) => shiftApi.backfillShift(pumpId, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shiftHistory', pumpId] })
      qc.invalidateQueries({ queryKey: ['activeShifts', pumpId] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, 'Failed to backfill shift. Please try again.')),
  })

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)

    // Validate
    if (!shiftDate)           return setError('Please select a shift date.')
    if (!shiftDefinitionId)   return setError('Please select a shift window.')
    if (!selectedDU)          return setError('Please select a Dispensary Unit.')
    if (!operatorId)          return setError('Please select an operator.')
    if (selectedNozzleIds.size === 0) return setError('Please select at least one nozzle.')

    const nozzleReadings: BackfillNozzleReading[] = []
    for (const nozzleId of selectedNozzleIds) {
      const opening = parseFloat(openingReadings[nozzleId] ?? '')
      const closing = parseFloat(closingReadings[nozzleId] ?? '')
      const nozzle  = availableNozzles.find((n) => n.id === nozzleId)
      if (!isFinite(opening) || opening < 0)
        return setError(`Opening reading is required for nozzle #${nozzle?.nozzleNumber ?? nozzleId}.`)
      if (!isFinite(closing) || closing < 0)
        return setError(`Closing reading is required for nozzle #${nozzle?.nozzleNumber ?? nozzleId}.`)
      if (closing < opening)
        return setError(`Closing reading cannot be less than opening reading for nozzle #${nozzle?.nozzleNumber ?? nozzleId}.`)
      nozzleReadings.push({ nozzleId, openingReading: opening, closingReading: closing })
    }

    // Validate credit entries
    const creditEntries: CreditEntryInput[] = []
    for (const row of creditRows) {
      if (!row.clientName.trim())
        return setError('Client name is required for all credit entries.')
      const amt = parseFloat(row.amount)
      if (!isFinite(amt) || amt <= 0)
        return setError(`Amount must be greater than 0 for credit entry: "${row.clientName}".`)
      creditEntries.push({
        clientName:  row.clientName.trim(),
        amount:      amt,
        fuelType:    row.fuelType || undefined,
        billNo:      row.billNo.trim() || undefined,
        description: row.description.trim() || undefined,
      })
    }

    const req: BackfillShiftRequest = {
      shiftDefinitionId,
      shiftDate,
      duId:              selectedDU.id,
      operatorId,
      nozzleReadings,
      cashCollected:      safeParse(cashCollected),
      upiCollected:       safeParse(upiCollected),
      cardCollected:      safeParse(cardCollected),
      fleetCardCollected: safeParse(fleetCardCollected),
      creditTotal:        safeParse(creditTotal),
      creditEntries,
      discrepancyReason: discrepancyReason.trim() || undefined,
    }

    mutation.mutate(req)
  }

  const isPending = mutation.isPending

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <ModalPortal>
      <div className="ui-modal-backdrop" onClick={!isPending ? onClose : undefined}>
        <div
          className="ui-modal-panel w-full max-w-xl overflow-hidden"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="ui-modal-header ui-modal-header--themed ui-modal-header--violet" style={{ background: 'linear-gradient(135deg, #7c3aed 0%, #5b21b6 100%)' }}>
            <div className="ui-modal-heading">
              <h2 className="ui-modal-title">Backfill Historical Shift</h2>
              <p className="ui-modal-subtitle">
                Enter past shift data for a date within the last year. Inventory deliveries must be recorded first.
              </p>
            </div>
            <button type="button" onClick={onClose} className="ui-btn ui-btn-ghost ui-modal-close" disabled={isPending}>
              ×
            </button>
          </div>

          {/* Body */}
          <form onSubmit={handleSubmit}>
            <div className="ui-modal-body space-y-5">

              {/* ── Section 1: Shift Identity ─────────────────────────────── */}
              <div className="bg-violet-50 border border-violet-100 rounded-xl p-4 space-y-4">
                <p className="text-xs font-semibold text-violet-700 uppercase tracking-wide">Shift Details</p>

                {/* Date */}
                <div>
                  <label className="ui-label">Shift Date <span className="text-red-500">*</span></label>
                  <input
                    type="date"
                    value={shiftDate}
                    min={minDate()}
                    max={maxDate()}
                    onChange={(e) => {
                      setShiftDate(e.target.value)
                      setShiftDefinitionId(null)
                    }}
                    className="ui-input"
                    required
                  />
                  <p className="text-xs text-slate-400 mt-1">Must be within the last 365 days. Cannot be today.</p>
                </div>

                {/* Shift Window */}
                <div>
                  <label className="ui-label">Shift Window <span className="text-red-500">*</span></label>
                  {defsLoading ? (
                    <div className="ui-input text-slate-400 text-sm">Loading windows for {shiftDate}…</div>
                  ) : definitions.length === 0 ? (
                    <div className="ui-input bg-amber-50 border-amber-200 text-amber-700 text-sm">
                      No shift schedule found for {shiftDate}. Configure shift definitions in Setup first.
                    </div>
                  ) : (
                    <select
                      value={shiftDefinitionId ?? ''}
                      onChange={(e) => setShiftDefinitionId(Number(e.target.value))}
                      className="ui-input"
                      required
                    >
                      <option value="">Select shift window…</option>
                      {definitions.map((def) => (
                        <option key={def.id} value={def.id}>
                          {def.name} · {def.windowLabel}
                        </option>
                      ))}
                    </select>
                  )}
                </div>

                {/* DU */}
                <div>
                  <label className="ui-label">Dispensary Unit (DU) <span className="text-red-500">*</span></label>
                  {dusLoading ? (
                    <div className="ui-input text-slate-400 text-sm">Loading DUs…</div>
                  ) : (
                    <select
                      value={selectedDU?.id ?? ''}
                      onChange={(e) => {
                        const du = dus.find((d) => d.id === Number(e.target.value)) ?? null
                        setSelectedDU(du)
                      }}
                      className="ui-input"
                      required
                    >
                      <option value="">Select DU…</option>
                      {dus.map((du) => (
                        <option key={du.id} value={du.id}>
                          {du.name} (DU #{du.duNumber})
                        </option>
                      ))}
                    </select>
                  )}
                </div>
              </div>

              {/* ── Section 2: Staff & Nozzles ────────────────────────────── */}
              <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 space-y-4">
                <p className="text-xs font-semibold text-slate-600 uppercase tracking-wide">Staff & Nozzles</p>

                {/* Operator */}
                <div>
                  <label className="ui-label">Operator on Duty <span className="text-red-500">*</span></label>
                  {operatorsLoading ? (
                    <div className="ui-input text-slate-400 text-sm">Loading operators…</div>
                  ) : (
                    <>
                      <input
                        type="text"
                        placeholder="Search operator name or ID…"
                        value={operatorSearch}
                        onChange={(e) => setOperatorSearch(e.target.value)}
                        className="ui-input mb-2"
                      />
                      <div className="max-h-32 overflow-y-auto border border-slate-200 rounded-lg divide-y divide-slate-100">
                        {filteredOperators.length === 0 ? (
                          <p className="text-xs text-slate-400 px-3 py-2">No operators found.</p>
                        ) : (
                          filteredOperators.map((op) => (
                            <button
                              key={op.id}
                              type="button"
                              onClick={() => { setOperatorId(op.id); setOperatorSearch('') }}
                              className={`w-full text-left px-3 py-2 text-sm transition-colors hover:bg-slate-50 ${
                                operatorId === op.id ? 'bg-violet-50 text-violet-700 font-medium' : 'text-slate-700'
                              }`}
                            >
                              {op.fullName}
                              <span className="ml-2 text-xs text-slate-400 font-mono">{op.employeeId}</span>
                            </button>
                          ))
                        )}
                      </div>
                      {operatorId && (
                        <p className="text-xs text-violet-700 mt-1">
                          Selected: {operators.find((o) => o.id === operatorId)?.fullName}
                        </p>
                      )}
                    </>
                  )}
                </div>

                {/* Nozzles */}
                {selectedDU && (
                  <div>
                    <label className="ui-label">Nozzles <span className="text-red-500">*</span></label>
                    {availableNozzles.length === 0 ? (
                      <p className="text-xs text-slate-400">No nozzles on this DU.</p>
                    ) : (
                      <div className="flex flex-wrap gap-2">
                        {availableNozzles.map((nozzle) => {
                          const selected = selectedNozzleIds.has(nozzle.id)
                          return (
                            <button
                              key={nozzle.id}
                              type="button"
                              onClick={() => toggleNozzle(nozzle.id)}
                              className={`px-3 py-1.5 rounded-lg border text-xs font-medium transition-colors ${
                                selected
                                  ? 'bg-violet-600 border-violet-600 text-white'
                                  : 'border-slate-300 text-slate-600 hover:border-violet-400 hover:text-violet-700'
                              }`}
                            >
                              #{nozzle.nozzleNumber} · {FUEL_LABELS[nozzle.fuelType] ?? nozzle.fuelType}
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* ── Section 3: Meter Readings ─────────────────────────────── */}
              {selectedNozzleIds.size > 0 && (
                <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 space-y-3">
                  <p className="text-xs font-semibold text-slate-600 uppercase tracking-wide">Meter Readings</p>
                  {[...selectedNozzleIds].map((nozzleId) => {
                    const nozzle = availableNozzles.find((n) => n.id === nozzleId)
                    if (!nozzle) return null
                    const liters = litersForNozzle(nozzleId)
                    return (
                      <div key={nozzleId} className="bg-white border border-slate-200 rounded-lg p-3 space-y-2">
                        <p className="text-xs font-semibold text-slate-700">
                          Nozzle #{nozzle.nozzleNumber} · {FUEL_LABELS[nozzle.fuelType] ?? nozzle.fuelType}
                        </p>
                        <div className="grid grid-cols-2 gap-3">
                          <div>
                            <label className="ui-label text-xs">Opening Reading <span className="text-red-500">*</span></label>
                            <input
                              type="number"
                              min="0"
                              step="0.01"
                              placeholder="0.00"
                              value={openingReadings[nozzleId] ?? ''}
                              onChange={(e) => setOpeningReadings((r) => ({ ...r, [nozzleId]: e.target.value }))}
                              className="ui-input"
                              required
                            />
                          </div>
                          <div>
                            <label className="ui-label text-xs">Closing Reading <span className="text-red-500">*</span></label>
                            <input
                              type="number"
                              min="0"
                              step="0.01"
                              placeholder="0.00"
                              value={closingReadings[nozzleId] ?? ''}
                              onChange={(e) => setClosingReadings((r) => ({ ...r, [nozzleId]: e.target.value }))}
                              className="ui-input"
                              required
                            />
                          </div>
                        </div>
                        {liters !== null && (
                          <p className="text-xs font-semibold text-emerald-700 bg-emerald-50 px-2 py-1 rounded-md inline-block">
                            {liters.toFixed(2)} L sold
                          </p>
                        )}
                        {liters !== null && liters < 0 && (
                          <p className="text-xs text-red-600">Closing reading must be ≥ opening reading.</p>
                        )}
                      </div>
                    )
                  })}
                </div>
              )}

              {/* ── Section 4: Payment Collection ────────────────────────── */}
              <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 space-y-3">
                <p className="text-xs font-semibold text-slate-600 uppercase tracking-wide">Payment Collected</p>
                <div className="grid grid-cols-2 gap-3">
                  {[
                    { label: 'Cash',       value: cashCollected,      set: setCashCollected },
                    { label: 'UPI',        value: upiCollected,       set: setUpiCollected },
                    { label: 'Card',       value: cardCollected,      set: setCardCollected },
                    { label: 'Fleet Card', value: fleetCardCollected, set: setFleetCardCollected },
                  ].map(({ label, value, set }) => (
                    <div key={label}>
                      <label className="ui-label text-xs">{label} (₹)</label>
                      <input
                        type="number"
                        min="0"
                        step="0.01"
                        placeholder="0.00"
                        value={value}
                        onChange={(e) => set(e.target.value)}
                        className="ui-input"
                      />
                    </div>
                  ))}
                </div>
              </div>

              {/* ── Section 5: Credit Sales ───────────────────────────────── */}
              <div className="bg-orange-50 border border-orange-100 rounded-xl p-4 space-y-3">
                <p className="text-xs font-semibold text-orange-700 uppercase tracking-wide">Credit Sales</p>

                <div>
                  <label className="ui-label text-xs">Credit Total (₹)</label>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    placeholder="0.00"
                    value={creditTotal}
                    onChange={(e) => setCreditTotal(e.target.value)}
                    className="ui-input"
                  />
                </div>

                {/* Credit entry rows */}
                {creditRows.map((row) => {
                  const isExpanded = expandedCreditId === row._key
                  return (
                    <div key={row._key} className="bg-white border border-orange-200 rounded-lg overflow-hidden">
                      {/* Collapsed summary */}
                      {!isExpanded && (
                        <div className="flex items-center justify-between px-3 py-2 text-xs">
                          <span className="font-medium text-slate-700 truncate">
                            {row.clientName || 'New entry'} {row.amount ? `· ₹${row.amount}` : ''}
                          </span>
                          <div className="flex items-center gap-1 shrink-0">
                            <button type="button" onClick={() => setExpandedCreditId(row._key)}
                              className="text-blue-600 hover:underline px-1">Edit</button>
                            <button type="button" onClick={() => removeCreditRow(row._key)}
                              className="text-red-500 hover:text-red-700 px-1">✕</button>
                          </div>
                        </div>
                      )}

                      {/* Expanded form */}
                      {isExpanded && (
                        <div className="p-3 space-y-2">
                          <div className="grid grid-cols-2 gap-2">
                            <div>
                              <label className="ui-label text-xs">Client Name <span className="text-red-500">*</span></label>
                              <input
                                type="text"
                                placeholder="Client name"
                                value={row.clientName}
                                onChange={(e) => updateCreditRow(row._key, { clientName: e.target.value })}
                                className="ui-input"
                              />
                            </div>
                            <div>
                              <label className="ui-label text-xs">Amount (₹) <span className="text-red-500">*</span></label>
                              <input
                                type="number"
                                min="0.01"
                                step="0.01"
                                placeholder="0.00"
                                value={row.amount}
                                onChange={(e) => updateCreditRow(row._key, { amount: e.target.value })}
                                className="ui-input"
                              />
                            </div>
                          </div>
                          <div className="grid grid-cols-2 gap-2">
                            <div>
                              <label className="ui-label text-xs">Fuel Type</label>
                              <select
                                value={row.fuelType}
                                onChange={(e) => updateCreditRow(row._key, { fuelType: e.target.value })}
                                className="ui-input"
                              >
                                <option value="">— optional —</option>
                                {Object.entries(FUEL_LABELS).map(([k, v]) => (
                                  <option key={k} value={k}>{v}</option>
                                ))}
                              </select>
                            </div>
                            <div>
                              <label className="ui-label text-xs">Bill No.</label>
                              <input
                                type="text"
                                placeholder="optional"
                                value={row.billNo}
                                onChange={(e) => updateCreditRow(row._key, { billNo: e.target.value })}
                                className="ui-input"
                              />
                            </div>
                          </div>
                          <div>
                            <label className="ui-label text-xs">Description</label>
                            <input
                              type="text"
                              placeholder="optional"
                              value={row.description}
                              onChange={(e) => updateCreditRow(row._key, { description: e.target.value })}
                              className="ui-input"
                            />
                          </div>
                          <div className="flex items-center justify-between pt-1">
                            <button
                              type="button"
                              onClick={() => removeCreditRow(row._key)}
                              className="text-xs text-red-500 hover:text-red-700"
                            >
                              Remove entry
                            </button>
                            <button
                              type="button"
                              disabled={!row.clientName.trim() || !row.amount}
                              onClick={() => setExpandedCreditId(null)}
                              className="ui-btn ui-btn-primary min-h-0 px-3 py-1 text-xs disabled:opacity-40"
                            >
                              Done
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  )
                })}

                <button
                  type="button"
                  onClick={addCreditRow}
                  className="w-full border border-dashed border-orange-300 rounded-lg py-2 text-xs text-orange-600 hover:border-orange-400 hover:bg-orange-100/50 transition-colors"
                >
                  + Add Credit Entry
                </button>
              </div>

              {/* ── Discrepancy reason ────────────────────────────────────── */}
              <div>
                <label className="ui-label">
                  Discrepancy Reason
                  <span className="text-slate-400 font-normal ml-1">(required if collected ≠ expected)</span>
                </label>
                <textarea
                  value={discrepancyReason}
                  onChange={(e) => setDiscrepancyReason(e.target.value)}
                  placeholder="Explain any difference between collected and expected amounts…"
                  rows={2}
                  className="ui-input resize-none"
                />
              </div>

              {/* Error */}
              {error && (
                <div className="ui-alert ui-alert-error text-sm">{error}</div>
              )}
            </div>

            {/* Footer */}
            <div className="ui-modal-footer">
              <button type="button" onClick={onClose} disabled={isPending} className="ui-btn ui-btn-ghost">
                Cancel
              </button>
              <button
                type="submit"
                disabled={isPending || !shiftDate || !shiftDefinitionId || !selectedDU || !operatorId || selectedNozzleIds.size === 0}
                className="ui-btn ui-btn-primary disabled:opacity-40"
                style={!isPending ? { background: 'linear-gradient(135deg, #7c3aed 0%, #5b21b6 100%)', border: 'none' } : {}}
              >
                {isPending ? 'Saving…' : 'Save Historical Shift'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </ModalPortal>
  )
}
