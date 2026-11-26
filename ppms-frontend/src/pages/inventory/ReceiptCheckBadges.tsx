import { AlertTriangle, CheckCircle2, CircleDashed, Gauge } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ReceiptCheckStatus, TankerDelivery } from '../../api/inventoryApi'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'

const STATUS_LOOK: Record<ReceiptCheckStatus, { tone: StatusTone; icon: LucideIcon }> = {
  OK: { tone: 'ok', icon: CheckCircle2 },
  SHORT: { tone: 'bad', icon: AlertTriangle },
  OVER: { tone: 'warn', icon: AlertTriangle },
  NOT_CHECKED: { tone: 'neutral', icon: CircleDashed },
}

const MOST_SERIOUS_FIRST: ReceiptCheckStatus[] = ['SHORT', 'OVER', 'NOT_CHECKED', 'OK']

/** The receipt-check result of one delivery line, or the most serious of an invoice's lines, plus any density flag. */
export function ReceiptCheckBadges({ lines }: { lines: TankerDelivery[] }) {
  const { t } = useTranslation('inventory')
  const status = MOST_SERIOUS_FIRST.find(candidate => lines.some(line => line.checkStatus === candidate)) ?? 'NOT_CHECKED'
  const { tone, icon } = STATUS_LOOK[status]
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      <StatusBadge tone={tone} icon={icon} label={t(`checkStatus.${status}`)} />
      {lines.some(line => line.densityFlagged) && (
        <StatusBadge tone="warn" icon={Gauge} label={t('checkStatus.densityFlag')} />
      )}
    </span>
  )
}
