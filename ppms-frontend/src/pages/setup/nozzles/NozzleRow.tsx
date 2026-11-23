import { useRef } from 'react'
import { AlertTriangle, Database, Wrench, Ruler, CircleOff } from 'lucide-react'
import type { TankInfo } from '../../../api/pumpApi'
import type { DUOption, NozzleDetail } from '../../../types/shift'
import { Spinner } from '../../../components/Spinner'
import { RowActionMenu } from '../../../components/RowActionMenu'
import { useRowPanelFocus } from '../../../hooks/useRowPanelFocus'
import { FUEL_LABELS } from '../../../constants/fuel'
import { FUEL_COLOR } from '../fuelStyles'
import type { NozzlePanel } from './nozzlePanels'
import { DisableNozzlePanel } from './DisableNozzlePanel'
import { MapTankPanel } from './MapTankPanel'
import { AdjustReadingPanel } from './AdjustReadingPanel'
import { RecordDipPanel } from './RecordDipPanel'
import { StatusBadge } from '../../../components/StatusBadge'

interface Props {
  pumpId: number
  du: DUOption
  nozzle: NozzleDetail
  tanks: TankInfo[]
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
  pumpId, du, nozzle, tanks, openPanel, onOpenPanel, onClosePanel,
  menuOpen, onMenuChange, statusPending, statusError, onEnable, onDisable,
}: Props) {
  const isInactive = nozzle.status === 'INACTIVE'
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  useRowPanelFocus(openPanel, panelRef, menuButtonRef)
  return (
    <div key={nozzle.id} className={`transition-opacity ${isInactive ? 'opacity-60' : ''}`}>
      {/* Nozzle row */}
      <div className="flex items-center justify-between px-4 py-2.5">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-bold text-ink">#{nozzle.nozzleNumber}</span>
          <span className={`text-xs px-2 py-0.5 rounded-badge font-medium border ${FUEL_COLOR[nozzle.fuelType] ?? 'bg-surface-sunken text-ink-2 border-border'}`}>
            {FUEL_LABELS[nozzle.fuelType] ?? nozzle.fuelType}
          </span>
          {isInactive && (
            <StatusBadge tone="warn" icon={Wrench} label="Maintenance" />
          )}
          {!isInactive && (
            <span className="text-xs text-ink-3">
              {FUEL_LABELS[nozzle.fuelType]}: {nozzle.lastReading}
            </span>
          )}
          {!isInactive && nozzle.tankId == null && (
            <span className="inline-flex items-center gap-1 text-xs text-warn font-medium">
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
              className="text-xs text-ok font-medium border border-ok hover:bg-ok-bg px-2.5 py-1 rounded-control transition-colors disabled:opacity-50"
            >
              {statusPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />Enabling…</span> : 'Enable'}
            </button>
          ) : (
            <RowActionMenu
              label="Nozzle actions"
              open={menuOpen}
              onOpenChange={(open) => onMenuChange(open ? nozzle.id : null)}
              triggerRef={menuButtonRef}
              triggerClassName="ui-btn ui-btn-ghost text-base leading-none text-ink-2 hover:text-ink"
              menuClassName="absolute right-0 top-full z-6 mt-2 w-56 overflow-hidden rounded-card border border-border bg-surface p-1.5 shadow-overlay"
              items={[
                { key: 'mapTank', label: 'Map Tank', description: 'Assign the supply tank', icon: <Database size={14} strokeWidth={2} />, className: 'text-ok hover:bg-ok-bg', onSelect: () => onOpenPanel('mapTank') },
                { key: 'reading', label: 'Adjust Reading', description: 'Correct stored meter value', icon: <Wrench size={14} strokeWidth={2} />, className: 'text-info hover:bg-info-bg', onSelect: () => onOpenPanel('reading') },
                { key: 'dip', label: 'Record Dip', description: 'Log a manual dip check', icon: <Ruler size={14} strokeWidth={2} />, className: 'text-warn hover:bg-warn-bg', onSelect: () => onOpenPanel('dip') },
                { key: 'disable', label: 'Disable for Maintenance', description: 'Temporarily take nozzle offline', icon: <CircleOff size={14} strokeWidth={2} />, className: 'text-bad hover:bg-bad-bg', onSelect: () => onOpenPanel('disable') },
              ]}
            />
          )}
        </div>
      </div>


      <div ref={panelRef}>
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
        <RecordDipPanel
          pumpId={pumpId}
          nozzle={nozzle}
          tank={tanks.find((t) => t.id === nozzle.tankId) ?? null}
          onClose={onClosePanel}
        />
      )}
      </div>
    </div>
  )
}
