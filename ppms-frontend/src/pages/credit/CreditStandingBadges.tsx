import { useTranslation } from 'react-i18next'
import { Hourglass } from 'lucide-react'
import type { CreditAgingRow } from '../../api/creditApi'
import { StatusBadge } from '../../components/StatusBadge'
import { creditStandingBadge, nearLimitBadge } from '../../components/statusBadges'

interface CreditStandingBadgesProps {
  row: Pick<CreditAgingRow, 'creditStatus' | 'nearLimit' | 'pendingPaymentCount'>
  className?: string
}

/** The account's standing, a near-limit warning when another status hides it, and payments waiting for approval. */
export function CreditStandingBadges({ row, className = 'flex flex-wrap items-center gap-1' }: CreditStandingBadgesProps) {
  const { t } = useTranslation('credit')
  return (
    <span className={className}>
      <StatusBadge {...creditStandingBadge(row.creditStatus)} />
      {row.nearLimit && row.creditStatus !== 'NEAR_LIMIT' && row.creditStatus !== 'OVER_LIMIT' && (
        <StatusBadge {...nearLimitBadge()} />
      )}
      {row.pendingPaymentCount > 0 && (
        <StatusBadge tone="info" icon={Hourglass} label={t('aging.pendingCount', { count: row.pendingPaymentCount })} />
      )}
    </span>
  )
}
