import { CheckCircle2, Clock, XCircle } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ShortageClaimStatus } from '../../../api/inventoryApi'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'

const LOOK: Record<ShortageClaimStatus, { tone: StatusTone; icon: LucideIcon }> = {
  RAISED: { tone: 'warn', icon: Clock },
  SETTLED: { tone: 'ok', icon: CheckCircle2 },
  REJECTED: { tone: 'bad', icon: XCircle },
}

export function ClaimStatusBadge({ status }: { status: ShortageClaimStatus }) {
  const { t } = useTranslation('inventory')
  const { tone, icon } = LOOK[status]
  return <StatusBadge tone={tone} icon={icon} label={t(`claims.status.${status}`)} />
}
