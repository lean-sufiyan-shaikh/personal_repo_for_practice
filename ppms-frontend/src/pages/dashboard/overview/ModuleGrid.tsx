import { Zap, Database, Settings, BarChart2, ReceiptText, Landmark } from 'lucide-react'
import { Reveal } from '../../../components/Reveal'
import { ModuleCard } from './ModuleCard'

export function ModuleGrid() {
  return (
    <Reveal delay={335}>
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
      <ModuleCard
        to="/dashboard/shifts"
        icon={Zap}
        title="Shifts"
        description="Open / close operator shifts, record meter readings and payment collection."
        color="from-emerald-500 to-emerald-600"
      />
      <ModuleCard
        to="/dashboard/inventory"
        icon={Database}
        title="Inventory"
        description="Log tanker deliveries, track FIFO stock levels, and record DIP checks."
        color="from-blue-500 to-blue-600"
      />
      <ModuleCard
        to="/dashboard/balance-sheets"
        icon={BarChart2}
        title="Balance Sheets"
        description="View daily and shift-level P&L, revenue, COGS, and net profit."
        color="from-violet-500 to-violet-600"
      />
      <ModuleCard
        to="/dashboard/expenses"
        icon={ReceiptText}
        title="Expenses"
        description="Track operational expenses and analyse cost trends by category."
        color="from-orange-500 to-orange-600"
      />
      <ModuleCard
        to="/dashboard/settlements"
        icon={Landmark}
        title="Settlements"
        description="Manage payment settlements and track partial payment collections."
        color="from-teal-500 to-teal-600"
      />
      <ModuleCard
        to="/dashboard/setup"
        icon={Settings}
        title="Setup"
        description="Configure pump locations, nozzles, fuel prices, staff, and credit clients."
        color="from-slate-500 to-slate-600"
      />
    </div>
    </Reveal>
  )
}
