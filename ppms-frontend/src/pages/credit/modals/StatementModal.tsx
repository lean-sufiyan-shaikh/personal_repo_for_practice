import { useState } from 'react'
import { creditApi } from '../../../api/creditApi'
import { ModalPortal } from '../../../components/ModalPortal'
import { istDateString } from '../../../utils/date'
import { whatsAppNumber, whatsAppShareUrl } from '../../../utils/whatsApp'
import { lastCompletedMonth } from '../creditDates'
import { statementMessage } from '../statementMessage'

type PeriodPreset = 'last-month' | 'this-month' | 'custom'

const PRESETS: { value: PeriodPreset; label: string }[] = [
  { value: 'last-month', label: 'Last month' },
  { value: 'this-month', label: 'This month' },
  { value: 'custom', label: 'Custom' },
]

function presetPeriod(preset: Exclude<PeriodPreset, 'custom'>, today: string) {
  if (preset === 'this-month') return { fromDate: `${today.slice(0, 8)}01`, toDate: today }
  const { periodFrom, periodTo } = lastCompletedMonth(today)
  return { fromDate: periodFrom, toDate: periodTo }
}

interface StatementModalProps {
  pumpId: number
  clientId: number
  clientName: string
  phone: string | null
  outstanding: number
  onClose: () => void
}

export function StatementModal({ pumpId, clientId, clientName, phone, outstanding, onClose }: StatementModalProps) {
  const today = istDateString()
  const [preset, setPreset] = useState<PeriodPreset>('last-month')
  const [period, setPeriod] = useState(() => presetPeriod('last-month', today))

  const choosePreset = (value: PeriodPreset) => {
    setPreset(value)
    if (value !== 'custom') setPeriod(presetPeriod(value, today))
  }

  const periodValid = period.fromDate !== '' && period.toDate !== '' && period.fromDate <= period.toDate
  const shareUrl = periodValid
    ? whatsAppShareUrl(phone, statementMessage(clientName, period.fromDate, period.toDate, outstanding))
    : null

  return (
    <ModalPortal onClose={onClose}>
      <div className="ui-modal-backdrop">
        <div className="ui-modal-panel w-full max-w-md">
          <div className="ui-modal-header ui-modal-header--themed ui-modal-header--info">
            <div className="ui-modal-heading">
              <h2 className="ui-modal-title">Statement</h2>
              <p className="ui-modal-subtitle">{clientName}</p>
            </div>
            <button type="button" aria-label="Close" onClick={onClose} className="ui-btn ui-btn-ghost ui-modal-close">&times;</button>
          </div>

          <div className="ui-modal-body space-y-4">
            <div role="group" aria-label="Statement period" className="ui-tabbar w-fit">
              {PRESETS.map(option => (
                <button key={option.value} type="button" aria-pressed={preset === option.value}
                  onClick={() => choosePreset(option.value)}
                  className={`ui-tabbar__button ${preset === option.value ? 'ui-tabbar__button--active' : ''}`}>
                  {option.label}
                </button>
              ))}
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="block">
                <span className="ui-label">From</span>
                <input type="date" value={period.fromDate} max={period.toDate || undefined}
                  onChange={e => { setPreset('custom'); setPeriod(current => ({ ...current, fromDate: e.target.value })) }}
                  className="text-sm" />
              </label>
              <label className="block">
                <span className="ui-label">To</span>
                <input type="date" value={period.toDate} min={period.fromDate || undefined} max={today}
                  onChange={e => { setPreset('custom'); setPeriod(current => ({ ...current, toDate: e.target.value })) }}
                  className="text-sm" />
              </label>
            </div>
            {!periodValid && (
              <p role="alert" className="text-sm text-bad">Choose a start date on or before the end date.</p>
            )}
            {!whatsAppNumber(phone) && (
              <p className="text-sm text-ink-3">Add a 10-digit mobile number to this client to share the statement on WhatsApp.</p>
            )}
          </div>

          <div className="ui-modal-footer">
            {shareUrl && (
              <a href={shareUrl} target="_blank" rel="noopener noreferrer" className="ui-btn ui-btn-secondary">
                Share on WhatsApp
              </a>
            )}
            {periodValid ? (
              <a href={creditApi.statementUrl(pumpId, clientId, period)} target="_blank" rel="noopener noreferrer"
                className="ui-btn ui-btn-primary">
                Open printable statement
              </a>
            ) : (
              <button type="button" disabled className="ui-btn ui-btn-primary">Open printable statement</button>
            )}
          </div>
        </div>
      </div>
    </ModalPortal>
  )
}
