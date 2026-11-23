import {
  Droplets, AlertTriangle, FileText, Wrench,
  Clock, TrendingDown, IndianRupee, Package, Lock, Info,
} from 'lucide-react'
import type { LucideProps } from 'lucide-react'

export function NotificationTypeIcon({ type }: { type: string }) {
  const base: LucideProps = { size: 15, strokeWidth: 1.75, 'aria-hidden': true }
  switch (type) {
    case 'LOW_STOCK':               return <Droplets      {...base} className="text-orange-400 flex-shrink-0 mt-0.5" />
    case 'PRICE_STALE':             return <AlertTriangle {...base} className="text-amber-400  flex-shrink-0 mt-0.5" />
    case 'DOCUMENT_EXPIRING':       return <FileText      {...base} className="text-slate-500  flex-shrink-0 mt-0.5" />
    case 'CALIBRATION_DUE':         return <Wrench        {...base} className="text-slate-500  flex-shrink-0 mt-0.5" />
    case 'CALIBRATION_DUE_SOON':    return <Wrench        {...base} className="text-amber-400  flex-shrink-0 mt-0.5" />
    case 'SHIFT_OVERDUE':           return <Clock         {...base} className="text-red-400    flex-shrink-0 mt-0.5" />
    case 'ZERO_SALE_SHIFT':         return <TrendingDown  {...base} className="text-slate-500  flex-shrink-0 mt-0.5" />
    case 'PRICE_CHANGE_OPEN_SHIFT': return <IndianRupee   {...base} className="text-blue-400   flex-shrink-0 mt-0.5" />
    case 'ANCILLARY_LOW_STOCK':     return <Package       {...base} className="text-slate-500  flex-shrink-0 mt-0.5" />
    case 'AUTO_CLOSED_SHIFT':       return <Lock          {...base} className="text-slate-500  flex-shrink-0 mt-0.5" />
    default:                        return <Info          {...base} className="text-slate-500  flex-shrink-0 mt-0.5" />
  }
}
