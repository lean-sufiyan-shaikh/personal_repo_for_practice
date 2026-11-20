import { AlertTriangle, Database, Wrench, Ruler, CircleOff } from 'lucide-react'
import type { FuelPrice, TankInfo } from '../../../api/pumpApi'
import type { DUOption, NozzleDetail } from '../../../types/shift'
import { Spinner } from '../../../components/Spinner'
import { IconButton } from '../../../components/IconButton'
import { FUEL_LABELS } from '../../../constants/fuel'
import { FUEL_COLOR } from '../fuelStyles'
import type { NozzlePanel } from './nozzlePanels'
import { DisableNozzlePanel } from './DisableNozzlePanel'
import { MapTankPanel } from './MapTankPanel'
import { AdjustReadingPanel } from './AdjustReadingPanel'
import { RecordDipPanel } from './RecordDipPanel'

interface Props {
  pumpId: number
  du: DUOption
  nozzle: NozzleDetail
  tanks: TankInfo[]
  currentPrices: FuelPrice[]
  openPanel: NozzlePanel | null
  onOpenPanel: (panel: NozzlePanel) => void
  onClosePanel: () => void
  menuOpen: boolean
  onMenuChange: (nozzleId: number | null) => void
  statusPending: boolean
  statusError: string | null
  onEnable: () => void
  onDisable: () => void
}

export function NozzleRow({
  pumpId, du, nozzle, tanks, currentPrices, openPanel, onOpenPanel, onClosePanel,
  menuOpen, onMenuChange, statusPending, statusError, onEnable, onDisable,
}: Props) {
  const isInactive = nozzle.status === 'INACTIVE'
  return (
    <div key={nozzle.id} className={`transition-opacity ${isInactive ? 'opacity-60' : ''}`}>
      {/* Nozzle row */}
      <div className="flex items-center justify-between px-4 py-2.5">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-bold text-slate-700">#{nozzle.nozzleNumber}</span>
          <span className={`text-xs px-2 py-0.5 rounded-full font-medium border ${FUEL_COLOR[nozzle.fuelType] ?? 'bg-slate-100 text-slate-600 border-slate-200'}`}>
            {FUEL_LABELS[nozzle.fuelType] ?? nozzle.fuelType}
          </span>
          {isInactive && (
            <span className="text-xs px-2 py-0.5 rounded-full font-medium border bg-orange-50 text-orange-700 border-orange-200">
              Maintenance
            </span>
          )}
          {!isInactive && (
            <span className="text-xs text-slate-500">
              {FUEL_LABELS[nozzle.fuelType]}: {nozzle.lastReading}
            </span>
          )}
          {!isInactive && nozzle.tankId == null && (
            <span className="inline-flex items-center gap-1 text-xs text-amber-600 font-medium">
              <AlertTriangle size={11} strokeWidth={2} />
              tank not mapped
            </span>
          )}
        </div>

        <div className="flex shrink-0 ml-2 items-center gap-2">
          {isInactive ? (
            <button
              type="button"
              onClick={() => onEnable()}
              disabled={statusPending}
              className="text-xs text-emerald-700 hover:text-emerald-900 font-medium border border-emerald-300 hover:border-emerald-500 px-2.5 py-1 rounded-md transition-colors disabled:opacity-50"
            >
              {statusPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />Enabling…</span> : 'Enable'}
            </button>
          ) : (
            <div className="relative">
              <IconButton
                onClick={() => onMenuChange(menuOpen ? null : nozzle.id)}
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                className="ui-btn ui-btn-ghost text-base leading-none text-slate-400 hover:text-slate-600"
    label="Nozzle actions"
    icon={'⋮'}
    />
              {menuOpen && (
                <>
                  <div className="fixed inset-0 z-[5]" onClick={() => onMenuChange(null)} />
                  <div className="absolute right-0 top-full z-[6] mt-2 w-56 overflow-hidden rounded-2xl border border-slate-200 bg-white p-1.5 shadow-[0_18px_40px_rgba(15,23,42,0.14)] backdrop-blur">
                    {(
                      [
                        { label: 'Map Tank', description: 'Assign the supply tank', icon: <Database size={14} strokeWidth={2} />, panel: 'mapTank'  as NozzlePanel, color: 'text-emerald-700 hover:bg-emerald-50' },
                        { label: 'Adjust Reading', description: 'Correct stored meter value', icon: <Wrench size={14} strokeWidth={2} />, panel: 'reading'  as NozzlePanel, color: 'text-blue-600 hover:bg-blue-50' },
                        { label: 'Record Dip', description: 'Log a manual dip check', icon: <Ruler size={14} strokeWidth={2} />, panel: 'dip'      as NozzlePanel, color: 'text-orange-600 hover:bg-orange-50' },
                        { label: 'Disable for Maintenance', description: 'Temporarily take nozzle offline', icon: <CircleOff size={14} strokeWidth={2} />, panel: 'disable' as NozzlePanel, color: 'text-red-600 hover:bg-red-50' },
                      ]
                    ).map(({ label, description, icon, panel, color }) => (
                      <button
                        key={panel}
                        type="button"
                        onClick={() => {
                          onOpenPanel(panel)
                          onMenuChange(null)
                        }}
                        className={`flex w-full items-start gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${color}`}
                      >
                        <span className="mt-0.5 shrink-0 leading-none">{icon}</span>
                        <span className="min-w-0">
                          <span className="block text-xs font-semibold leading-5">{label}</span>
                          <span className="block text-[11px] leading-4 text-slate-500">{description}</span>
                        </span>
                      </button>
                    ))}
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      </div>


      {openPanel === 'disable' && (
        <DisableNozzlePanel
          nozzleNumber={nozzle.nozzleNumber}
          error={statusError}
          isPending={statusPending}
          onDisable={onDisable}
          onCancel={onClosePanel}
        />
      )}

      {openPanel === 'mapTank' && (
        <MapTankPanel pumpId={pumpId} duId={du.id} nozzle={nozzle} tanks={tanks} onClose={onClosePanel} />
      )}

      {openPanel === 'reading' && (
        <AdjustReadingPanel pumpId={pumpId} nozzle={nozzle} onClose={onClosePanel} />
      )}

      {openPanel === 'dip' && (
        <RecordDipPanel pumpId={pumpId} nozzle={nozzle} currentPrices={currentPrices} onClose={onClosePanel} />
      )}
    </div>
  )
}
