import { FlaskConical } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { StatusBadge } from './StatusBadge'

/** Marks a pump of sample data wherever the pump is named. */
export function DemoBadge({ className = '' }: { className?: string }) {
  const { t } = useTranslation('common')
  return <StatusBadge tone="info" icon={FlaskConical} label={t('badges.demo')} className={className} />
}
