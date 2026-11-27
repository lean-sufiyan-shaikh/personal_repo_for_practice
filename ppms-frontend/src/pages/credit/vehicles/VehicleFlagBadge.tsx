import { AlertTriangle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { VehicleFlag } from '../../../api/vehicleApi'
import { StatusBadge } from '../../../components/StatusBadge'

/** Marks a credit entry a WARN account let through outside its vehicle rules, e.g. "Unknown vehicle". */
export function VehicleFlagBadge({ flag }: { flag: VehicleFlag }) {
  const { t } = useTranslation('credit')
  return <StatusBadge tone="warn" icon={AlertTriangle} label={t(`vehicleEntry.flags.${flag}`)} />
}
