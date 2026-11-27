import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { AccountFigure, PaymentPromise } from '../../../api/collectionApi'
import { MoneyText } from '../../../components/AmountText'
import { PageSection } from '../../../components/layout/PageSection'
import { StatusBadge } from '../../../components/StatusBadge'
import { formatIstDate } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

const FOLLOW_UP_SEARCH = '?records=followUp'

/** Accounts with what they owe and how long overdue, each opening the client's follow-up. */
export function AccountFigureList({ title, accounts, emptyText }: { title: string; accounts: AccountFigure[]; emptyText: string }) {
  const { t } = useTranslation('collections')
  return (
    <PageSection title={title}>
      {accounts.length === 0 ? <p className="ui-empty">{emptyText}</p> : (
        <ul className="divide-y divide-border">
          {accounts.map((account) => (
            <li key={account.clientId} className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 py-2">
              <Link to={`/dashboard/credit/${account.clientId}${FOLLOW_UP_SEARCH}`}
                className="min-w-0 break-words font-medium text-primary hover:underline">
                {account.name}
              </Link>
              <span className="text-sm text-ink-3">
                <MoneyText value={account.outstanding} className="font-semibold text-ink" />
                {' · '}{t('dashboard.daysOverdue', { days: account.daysOverdue })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </PageSection>
  )
}

function PromiseRows({ promises }: { promises: PaymentPromise[] }) {
  const { t } = useTranslation('collections')
  return (
    <ul className="divide-y divide-border">
      {promises.map((promise) => (
        <li key={promise.id} className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 py-2">
          <Link to={`/dashboard/credit/${promise.clientId}${FOLLOW_UP_SEARCH}`}
            className="min-w-0 break-words font-medium text-primary hover:underline">
            {promise.clientName}
          </Link>
          <span className="text-sm text-ink-2">
            {t('promise.summary', { amount: formatCurrency(promise.amount), date: formatIstDate(promise.promisedDate) })}
          </span>
          {promise.status === 'MISSED' && promise.paidAmount != null && (
            <StatusBadge tone="bad" label={t('promise.paid', { amount: formatCurrency(promise.paidAmount) })} />
          )}
        </li>
      ))}
    </ul>
  )
}

/** Promises falling due today and those missed in the last 30 days. */
export function PromiseLists({ dueToday, missed }: { dueToday: PaymentPromise[]; missed: PaymentPromise[] }) {
  const { t } = useTranslation('collections')
  return (
    <PageSection title={t('dashboard.promisesTitle')}>
      {dueToday.length === 0 && missed.length === 0 ? <p className="ui-empty">{t('dashboard.noPromises')}</p> : (
        <div className="space-y-4">
          {dueToday.length > 0 && (
            <div>
              <h3 className="ui-section-heading">{t('dashboard.dueToday')}</h3>
              <PromiseRows promises={dueToday} />
            </div>
          )}
          {missed.length > 0 && (
            <div>
              <h3 className="ui-section-heading">{t('dashboard.missed')}</h3>
              <PromiseRows promises={missed} />
            </div>
          )}
        </div>
      )}
    </PageSection>
  )
}
