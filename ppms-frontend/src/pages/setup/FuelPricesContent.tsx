import { useState } from 'react'
import { isAxiosError } from 'axios'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Check } from 'lucide-react'
import { parseApiError } from '../../utils/apiError'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary, PriceDeviationWarning, FuelPrice } from '../../api/pumpApi'
import type { FuelType } from '../../types/shift'
import { formatIstDate } from '../../utils/date'
import { isPriceStale } from '../../utils/fuelPrices'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { MoneyText } from '../../components/AmountText'
import { IconButton } from '../../components/IconButton'
import { FUEL_LABELS, FUEL_UNIT } from '../../constants/fuel'

// ── Fuel prices content ───────────────────────────────────────────────────────

export function FuelPricesContent({ pump, currentPrices }: { pump: PumpSummary; currentPrices: FuelPrice[] }) {
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  // Dynamic price inputs keyed by fuel type (only for fuel types actually present on this pump)
  const [prices, setPrices] = useState<Record<string, string>>({})
  const [error, setError]   = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  // Set to true immediately after a successful save so the alert disappears without waiting for query refetch
  const [pricesConfirmedToday, setPricesConfirmedToday] = useState(false)
  // 15% deviation warning state — set when backend returns HTTP 409
  const [deviationWarning, setDeviationWarning] = useState<(PriceDeviationWarning & { fuelType: string }) | null>(null)
  // P2.3 — open shifts warning shown after a successful price update
  const [openShiftsWarning, setOpenShiftsWarning] = useState<string | null>(null)

  // Derive the unique fuel types across all DU nozzles
  const availableFuelTypes = [...new Set(
    pump.dus.flatMap((d) => d.nozzles.map((n) => n.fuelType))
  )] as FuelType[]

  const mutation = useMutation({
    mutationFn: pumpApi.setFuelPrice,
    onError: (err) => {
      const warning = isAxiosError<Partial<PriceDeviationWarning>>(err) && err.response?.status === 409 ? err.response.data : undefined
      if (warning?.deviationPercent != null) {
        // Backend returned a PriceDeviationWarning (has deviationPercent) — show confirmation dialog
        // Determine which fuel type triggered the warning
        const ft = availableFuelTypes.find((f) => prices[f] && Number(prices[f]) > 0) ?? ''
        setDeviationWarning({ ...(warning as PriceDeviationWarning), fuelType: ft })
      } else {
        // 409 from a DB constraint violation, or any other error — show the message directly
        setError(parseApiError(err, 'Failed to set price'))
      }
    },
  })

  const getCurrentPrice = (fuelType: string) =>
    currentPrices.find((p) => p.fuelType === fuelType)?.pricePerUnit

  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setError(null); setSuccess(false); setDeviationWarning(null); setOpenShiftsWarning(null)
    const tasks = availableFuelTypes
      .filter((ft) => prices[ft] && Number(prices[ft]) > 0)
      .map((ft) => mutation.mutateAsync({ pumpId: pump.id, fuelType: ft, pricePerUnit: Number(prices[ft]) }))
    if (tasks.length === 0) { setError('Enter at least one price to save.'); return }
    try {
      const results = await Promise.all(tasks)
      queryClient.invalidateQueries({ queryKey: ['fuelPrices', pump.id] })
      setPrices({})
      setPricesConfirmedToday(true)
      setSuccess(true); setTimeout(() => setSuccess(false), 3000)
      addToast('Fuel prices updated', 'success')
      // Show open-shifts warning if any price update detected open shifts
      const warning = results.find((r) => r.openShiftsWarning)?.openShiftsWarning ?? null
      setOpenShiftsWarning(warning)
    } catch { /* error already set by onError */ }
  }

  // Confirms prices are unchanged from yesterday — re-saves current prices for today
  // so the stale alert is dismissed without requiring manual re-entry.
  const [confirmingUnchanged, setConfirmingUnchanged] = useState(false)
  const confirmUnchanged = async () => {
    setError(null); setSuccess(false); setOpenShiftsWarning(null); setConfirmingUnchanged(true)
    const tasks = currentPrices.map((p) =>
      mutation.mutateAsync({ pumpId: pump.id, fuelType: p.fuelType as FuelType, pricePerUnit: Number(p.pricePerUnit) })
    )
    try {
      const results = await Promise.all(tasks)
      queryClient.invalidateQueries({ queryKey: ['fuelPrices', pump.id] })
      setPricesConfirmedToday(true)
      setSuccess(true); setTimeout(() => setSuccess(false), 3000)
      addToast('Prices confirmed for today', 'success')
      const warning = results.find((r) => r.openShiftsWarning)?.openShiftsWarning ?? null
      setOpenShiftsWarning(warning)
    } catch { /* error already set by onError */ }
    finally { setConfirmingUnchanged(false) }
  }

  const confirmDeviation = async () => {
    if (!deviationWarning) return
    setDeviationWarning(null); setError(null)
    try {
      const result = await mutation.mutateAsync({
        pumpId: pump.id,
        fuelType: deviationWarning.fuelType as FuelType,
        pricePerUnit: deviationWarning.newPrice,
        confirmed: true,
      })
      queryClient.invalidateQueries({ queryKey: ['fuelPrices', pump.id] })
      setPrices({})
      setPricesConfirmedToday(true)
      setSuccess(true); setTimeout(() => setSuccess(false), 3000)
      if (result.openShiftsWarning) setOpenShiftsWarning(result.openShiftsWarning)
    } catch (err) {
      setError(parseApiError(err, 'Failed to set price'))
    }
  }

  const pricesStale = !pricesConfirmedToday && currentPrices.length > 0 && isPriceStale(currentPrices)

  if (pump.dus.length === 0) {
    return <p className="text-xs text-ink-3">Add at least one DU with a nozzle first (Nozzles section above).</p>
  }

  return (
    <div className="space-y-3">
      {/* Stale price alert — shown when no price has been set today */}
      {pricesStale && (() => {
        const lastUpdatedOn = currentPrices.reduce((latest, p) =>
          p.effectiveFrom > latest ? p.effectiveFrom : latest, currentPrices[0].effectiveFrom)
        return (
          <div className="flex items-start justify-between gap-3 bg-bad-bg border border-bad rounded-control px-4 py-3">
            <div className="flex items-start gap-2.5">
              <AlertTriangle size={16} strokeWidth={2} className="text-bad mt-0.5 shrink-0" />
              <div>
                <p className="text-sm font-semibold text-bad">Fuel prices not updated today</p>
                <p className="text-xs text-bad mt-0.5">
                  Last updated on{' '}
                  <span className="font-medium">{formatIstDate(lastUpdatedOn)}</span>
                  . Enter new prices below, or confirm they are unchanged.
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={confirmUnchanged}
              disabled={confirmingUnchanged || mutation.isPending}
              className="shrink-0 inline-flex items-center gap-1.5 text-xs font-semibold bg-surface text-ok border border-ok hover:bg-ok-bg active:bg-ok-bg px-3 py-1.5 rounded-control transition-colors disabled:opacity-60 whitespace-nowrap"
            >
              {confirmingUnchanged
                ? <><Spinner className="w-3.5 h-3.5" /><span>Confirming…</span></>
                : <><span>Same prices</span><Check size={13} strokeWidth={2.5} className="text-ok" /></>
              }
            </button>
          </div>
        )
      })()}

      {/* Current prices summary */}
      {currentPrices.length > 0 && (
        <div className="flex flex-wrap gap-3">
          {currentPrices.map((p) => (
            <div key={p.id} className="flex items-center gap-1.5 bg-ok-bg border border-ok rounded-control px-3 py-1.5">
              <span className="text-xs font-medium text-ok">{FUEL_LABELS[p.fuelType] ?? p.fuelType}</span>
              <span className="text-sm font-bold text-ok">
                <MoneyText value={p.pricePerUnit} />/{FUEL_UNIT[p.fuelType] ?? 'L'}
              </span>
            </div>
          ))}
        </div>
      )}

      <form onSubmit={submit} className="space-y-3">
        <div className="flex flex-wrap gap-3 items-end">
          {availableFuelTypes.map((ft) => (
            <div key={ft}>
              <label className="block">
                <span className="ui-label">
                {FUEL_LABELS[ft]} (₹/{FUEL_UNIT[ft]})
                {getCurrentPrice(ft) != null && (
                  <span className="text-ink-3 ml-1">current: ₹{getCurrentPrice(ft)}</span>
                )}
              </span>
                <input type="number" step="0.01" min="0.01"
                  value={prices[ft] ?? ''}
                  onChange={(e) => setPrices((prev) => ({ ...prev, [ft]: e.target.value }))}
                  className="w-32 text-sm"
                  placeholder="e.g. 96.72" />
              </label>
            </div>
          ))}
          <button type="submit" disabled={mutation.isPending}
            className="ui-btn ui-btn-primary">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />Saving…</span> : 'Save Prices'}
          </button>
        </div>
        {error   && <p role="alert" className="ui-error-text">{error}</p>}
        {success && <p className="text-ok text-xs">Prices saved successfully.</p>}
      </form>

      {/* P2.3 — Open-shifts warning after price change */}
      {openShiftsWarning && (
        <div className="flex items-start gap-2.5 bg-warn-bg border border-warn rounded-control px-4 py-3">
          <AlertTriangle size={16} strokeWidth={2} className="text-warn mt-0.5 shrink-0" />
          <div className="flex-1">
            <p className="text-sm font-semibold text-warn-strong">Open Shifts Detected</p>
            <p className="text-xs text-warn mt-0.5">{openShiftsWarning}</p>
          </div>
          <IconButton onClick={() => setOpenShiftsWarning(null)} className="text-warn hover:text-warn-strong text-lg leading-none shrink-0"
            label="Dismiss warning"
            icon={'×'}
          />
        </div>
      )}

      {/* 15% deviation confirmation dialog */}
      {deviationWarning && (
        <Modal
          title="Large Price Change Detected"
          subtitle={deviationWarning.message}
          tone="warning"
          size="sm"
          onClose={() => setDeviationWarning(null)}
          bodyClassName="space-y-4"
          footer={
            <>
              <button type="button" onClick={() => setDeviationWarning(null)} className="ui-btn ui-btn-secondary flex-1">
                Cancel
              </button>
              <button type="button" onClick={confirmDeviation} className="ui-btn ui-btn-warning flex-1">
                Yes, Set Price
              </button>
            </>
          }
        >
          <div className="bg-warn-bg border border-warn rounded-control px-4 py-3 text-xs space-y-1">
            <div className="flex justify-between">
              <span className="text-ink-3">Last price</span>
              <MoneyText value={deviationWarning.lastPrice} className="font-semibold text-ink" />
            </div>
            <div className="flex justify-between">
              <span className="text-ink-3">New price</span>
              <MoneyText value={deviationWarning.newPrice} className="font-semibold text-ink" />
            </div>
            <div className="flex justify-between">
              <span className="text-ink-3">Deviation</span>
              <span className="font-bold text-warn">{deviationWarning.deviationPercent.toFixed(1)}%</span>
            </div>
          </div>
          <p className="text-xs text-ink-3">
            Are you sure you want to set this price? This is a significant deviation from the last recorded price.
          </p>
        </Modal>
      )}
    </div>
  )
}
