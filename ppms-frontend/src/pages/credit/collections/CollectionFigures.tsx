import { useTranslation } from 'react-i18next'
import type { CollectionDashboard } from '../../../api/collectionApi'
import { KpiRow } from '../../../components/layout/DashboardGrid'
import { PageSection } from '../../../components/layout/PageSection'
import { MoneyText } from '../../../components/AmountText'
import { formatCurrency } from '../../../utils/format'

const AGE_BUCKETS = ['upTo30Days', 'days31To60', 'days61To90', 'over90Days'] as const

function Figure({ label, value, note, tone }: { label: string; value: string; note?: string; tone?: string }) {
  return (
    <div className="ui-dashboard-module px-4 py-3">
      <p className="ui-kpi-label">{label}</p>
      <p className={`mt-1 text-lg font-bold tabular-nums ${tone ?? 'text-ink'}`}>{value}</p>
      {note && <p className="text-xs text-ink-3">{note}</p>}
    </div>
  )
}

/** Overdue total, this month's collections, holds and promises due today, then how old the balance is. */
export function CollectionFigures({ dashboard }: { dashboard: CollectionDashboard }) {
  const { t } = useTranslation('collections')
  const total = AGE_BUCKETS.reduce((sum, bucket) => sum + dashboard.ageBuckets[bucket], 0)
  return (
    <>
      <KpiRow label={t('dashboard.summaryLabel')} columns={4}>
        <Figure label={t('dashboard.totalOverdue')} value={formatCurrency(dashboard.totalOverdue)}
          note={t('dashboard.overdueAccounts', { count: dashboard.overdueClientCount })}
          tone={dashboard.totalOverdue > 0 ? 'text-bad' : undefined} />
        <Figure label={t('dashboard.collectedThisMonth')} value={formatCurrency(dashboard.collectedThisMonth)} tone="text-ok" />
        <Figure label={t('dashboard.onHold')} value={String(dashboard.clientsOnHold.length)}
          tone={dashboard.clientsOnHold.length > 0 ? 'text-bad' : undefined} />
        <Figure label={t('dashboard.promisesToday')} value={String(dashboard.promisesDueToday.length)} />
      </KpiRow>
      <PageSection title={t('dashboard.ageTitle')}>
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {AGE_BUCKETS.map((bucket) => (
            <div key={bucket} className="space-y-1">
              <dt className="text-xs text-ink-3">{t(`dashboard.age.${bucket}`)}</dt>
              <dd><MoneyText value={dashboard.ageBuckets[bucket]} className="font-semibold text-ink" /></dd>
              <dd aria-hidden="true" className="h-1.5 overflow-hidden rounded bg-surface-sunken">
                <div className="h-full bg-warn" style={{ width: `${total > 0 ? (dashboard.ageBuckets[bucket] / total) * 100 : 0}%` }} />
              </dd>
            </div>
          ))}
        </dl>
      </PageSection>
    </>
  )
}
