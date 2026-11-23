import { usePumpStore } from '../../store/usePumpStore'
import { Reveal } from '../../components/Reveal'
import { MoneyText } from '../../components/AmountText'
import { CashEntryForm } from './CashEntryForm'
import { CashEventList } from './CashEventList'
import { useCashEvents } from './useCashEvents'

export default function CashDrawerPage() {
  const { selectedPumpId: pumpId } = usePumpStore()

  const events = useCashEvents(pumpId)
  const { cashData, cashError } = events
  const balance = cashData?.currentBalance ?? 0

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* ── Header ── */}
      <Reveal delay={60}>
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">Cash flow</p>
          <h1 className="ui-title-sm">Cash Drawer</h1>
          <p className="ui-subtitle">Track cash movements in and out of the pump safe</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Balance</span>
            <MoneyText value={balance} className="ui-section-meta-value" />
          </div>
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">Events</span>
            <span className="ui-section-meta-value">{cashData?.events?.totalElements ?? 0}</span>
          </div>
        </div>
      </div>
      </Reveal>

      {cashError && (
        <div className="ui-alert ui-alert-warning text-sm">
          Could not load cash events. Check your connection and refresh.
        </div>
      )}

      {/* ── Balance card ── */}
      <Reveal delay={130}>
      <div className="ui-card border-l-4 border-l-primary px-6 py-5">
        <p className="text-sm text-ink-2">Current Balance</p>
        <MoneyText value={balance} className="mt-1 block text-3xl font-bold text-ink" />
        <p className="mt-1 text-xs text-ink-3">Calculated from all cash-in minus cash-out events</p>
      </div>
      </Reveal>

      <CashEntryForm pumpId={pumpId} balance={balance} />

      {/* ── Event list ── */}
      <CashEventList events={events} />
    </div>
  )
}
