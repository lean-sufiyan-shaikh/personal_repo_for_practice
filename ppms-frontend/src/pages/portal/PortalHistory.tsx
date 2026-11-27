import { useState } from 'react'
import { isAxiosError } from 'axios'
import { useTranslation } from 'react-i18next'
import { MoneyText } from '../../components/AmountText'
import { StatusBadge } from '../../components/StatusBadge'
import { formatIstDate } from '../../utils/date'
import { portalApi } from './portalClient'
import type { PortalStatementMonth, PortalTransaction } from './portalClient'

const CREDIT_TYPES: readonly string[] = ['PAYMENT', 'CREDIT_NOTE', 'WRITE_OFF', 'DEPOSIT_APPLIED']

type FailureKey = 'history.tooMany' | 'history.sessionEnded' | 'history.failed'

function failureKey(err: unknown): FailureKey {
  const status = isAxiosError(err) ? err.response?.status : undefined
  if (status === 429) return 'history.tooMany'
  if (status === 400) return 'history.sessionEnded'
  return 'history.failed'
}

/** The last 90 days of the client's own transactions, and this or last month's statement, within the PIN session. */
export function PortalHistory({ sessionToken }: { sessionToken: string }) {
  const { t } = useTranslation('portal')
  const [rows, setRows] = useState<PortalTransaction[] | null>(null)
  const [error, setError] = useState<FailureKey | null>(null)
  const [busy, setBusy] = useState(false)

  const showHistory = async () => {
    setBusy(true)
    setError(null)
    try {
      setRows(await portalApi.getHistory(sessionToken))
    } catch (err) {
      setError(failureKey(err))
    } finally {
      setBusy(false)
    }
  }

  const openStatement = async (month: PortalStatementMonth) => {
    setError(null)
    try {
      const html = await portalApi.getStatementHtml(sessionToken, month)
      const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
      window.open(url, '_blank', 'noopener')
    } catch (err) {
      setError(failureKey(err))
    }
  }

  return (
    <div className="mt-4 space-y-3">
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={showHistory} disabled={busy} className="ui-btn ui-btn-secondary">
          {t('history.show')}
        </button>
        <button type="button" onClick={() => openStatement('CURRENT')} className="ui-btn ui-btn-secondary">
          {t('history.thisMonth')}
        </button>
        <button type="button" onClick={() => openStatement('PREVIOUS')} className="ui-btn ui-btn-secondary">
          {t('history.lastMonth')}
        </button>
      </div>
      {error && <p role="alert" className="ui-alert ui-alert-danger text-xs">{t(error)}</p>}
      {rows && (rows.length === 0 ? (
        <p className="text-xs text-ink-3">{t('history.empty')}</p>
      ) : (
        <ul aria-label={t('history.title')} className="divide-y divide-border">
          {rows.map((row, index) => (
            <li key={`${row.date}-${row.type}-${index}`} className="flex items-start justify-between gap-3 py-2">
              <div className="min-w-0">
                <p className="text-xs text-ink-3">
                  {formatIstDate(row.date, { day: '2-digit', month: 'short' })}{row.accountName ? ` · ${row.accountName}` : ''}
                </p>
                <p className="text-sm text-ink break-words">{row.reference ?? t(`history.types.${row.type}` as 'history.types.SALE', { defaultValue: row.type })}</p>
                {row.disputed && <StatusBadge tone="warn" label={t('history.disputed')} />}
              </div>
              <MoneyText value={CREDIT_TYPES.includes(row.type) ? -row.amount : row.amount} showSign
                className={`shrink-0 text-sm font-semibold ${CREDIT_TYPES.includes(row.type) ? 'text-ok' : 'text-ink'}`} />
            </li>
          ))}
        </ul>
      ))}
    </div>
  )
}
