import { Link } from 'react-router-dom'
import { type LucideIcon } from 'lucide-react'

// ── Module navigation card ─────────────────────────────────────────────────────

export function ModuleCard({
  to, icon: Icon, title, description, color,
}: {
  to: string
  icon: LucideIcon
  title: string
  description: string
  color: string
}) {
  return (
    <Link to={to}>
      <div className="ui-dashboard-module overflow-hidden transition-all duration-200 border-slate-200 hover:shadow-md hover:-translate-y-0.5 cursor-pointer">
        <div className={`bg-gradient-to-r ${color} px-4 py-3.5 flex items-center gap-3`}>
          <Icon size={18} className="text-white flex-shrink-0" strokeWidth={2} />
          <span className="text-sm font-bold text-white">{title}</span>
        </div>
        <div className="bg-white px-4 py-3.5">
          <p className="text-xs text-slate-500 leading-relaxed">{description}</p>
          <div className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-blue-600">
            <span>Open module</span>
            <span>→</span>
          </div>
        </div>
      </div>
    </Link>
  )
}
