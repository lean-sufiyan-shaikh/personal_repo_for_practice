import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Gauge, Database, IndianRupee, Users, Clock, Settings, Truck, Receipt } from 'lucide-react'
import { pumpApi } from '../../api/pumpApi'
import { userApi } from '../../api/userApi'
import { useAuthStore } from '../../store/authStore'
import type { PumpSummary, SetupChecklist, SetupStep } from '../../api/pumpApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import type { StatusTone } from '../../components/StatusBadge'
import { AccordionSection } from './AccordionSection'
import { FuelPricesContent } from './FuelPricesContent'
import { GstDetailsContent } from './GstDetailsContent'
import { PumpSettingsContent } from './PumpSettingsContent'
import { TanksContent } from './TanksContent'
import { NozzleContent } from './nozzles/NozzleContent'
import type { SectionKey } from './sectionKey'
import { ShiftDefinitionsContent } from './shiftDefinitions/ShiftDefinitionsContent'
import { StaffContent } from './staff/StaffContent'
import { TankersContent } from './tankers/TankersContent'
import { isPriceStale } from '../../utils/fuelPrices'

const COMPLETE_BADGE: StatusTone = 'ok'
const NEEDS_ATTENTION_BADGE: StatusTone = 'warn'
const NEUTRAL_BADGE: StatusTone = 'neutral'

const SECTION_KEYS: SectionKey[] = ['nozzles', 'tanks', 'prices', 'staff', 'shifts', 'tankers', 'settings', 'gst']

function stepBadge(checklist: SetupChecklist | undefined, step: SetupStep): StatusTone {
  const item = checklist?.items.find((entry) => entry.step === step)
  if (!item) return NEUTRAL_BADGE
  return item.done ? COMPLETE_BADGE : NEEDS_ATTENTION_BADGE
}

/** checklist is passed for owners and admins; it colours each section's badge by whether that step is done. */
export function PumpManagementPanel({
  pump,
  onPumpUpdated,
  checklist,
}: {
  pump: PumpSummary
  onPumpUpdated: () => void
  checklist?: SetupChecklist
}) {
  const { t } = useTranslation('setup')
  const { user } = useAuthStore()
  const isManager = user?.role === 'MANAGER'
  const [searchParams, setSearchParams] = useSearchParams()
  const sectionParam = searchParams.get('section')
  const openSection = SECTION_KEYS.find((key) => key === sectionParam) ?? null

  useEffect(() => {
    if (openSection) document.getElementById(`setup-section-${openSection}`)?.scrollIntoView?.({ block: 'start' })
  }, [openSection])

  // Load summary data for accordion headers
  const { data: currentPrices = [] } = useQuery({
    queryKey: ['fuelPrices', pump.id],
    queryFn:  () => pumpApi.getCurrentPrices(pump.id),
  })
  const { data: staff = [] } = useQuery({
    queryKey: ['staff', pump.id],
    queryFn:  () => userApi.getStaff(pump.id),
  })
  const { data: shiftDefs = [] } = useQuery({
    queryKey: ['shift-definitions', pump.id],
    queryFn:  () => shiftDefinitionApi.getAll(pump.id),
  })

  const toggle = (key: SectionKey) =>
    setSearchParams((params) => {
      const updated = new URLSearchParams(params)
      if (openSection === key) updated.delete('section')
      else updated.set('section', key)
      return updated
    }, { replace: true })

  const pricesStale = isPriceStale(currentPrices)
  const priceSummary = currentPrices.length === 0
    ? t('sections.prices.notSet')
    : currentPrices.map((p) => `${p.fuelType.charAt(0)}: ₹${p.pricePerUnit}`).join(' · ')
      + (pricesStale ? t('sections.prices.notUpdatedToday') : '')

  return (
    <div className="ui-card p-0">

      {/* Pump identity header */}
      <div className="px-5 py-4 bg-ink rounded-t-card">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="font-bold text-surface text-base">{pump.name}</h2>
            <p className="text-on-dark-2 text-xs mt-0.5">{pump.address}</p>
          </div>
          <div className="flex gap-2">
            <span className="bg-white/10 text-on-dark-2 text-xs px-2.5 py-1 rounded-badge">
              {t('sections.duUsage', { used: pump.dus.length, max: pump.maxDuCount })}
            </span>
          </div>
        </div>
      </div>

      {/* Accordion sections */}
      <div className="divide-y divide-border">

        {/* ── Manager access note ── */}
        {isManager && (
          <div className="flex items-start gap-2.5 px-5 py-3 bg-info-bg/60 border-b border-info">
            <svg className="w-4 h-4 text-primary mt-0.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M13 16h-1v-4h-1m1-4h.01M12 2a10 10 0 100 20A10 10 0 0012 2z" />
            </svg>
            <p className="text-xs text-info">
              {t('sections.managerNote')}
            </p>
          </div>
        )}

        {/* ── Nozzles — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="nozzles"
            open={openSection === 'nozzles'}
            onToggle={() => toggle('nozzles')}
            icon={<div className="w-7 h-7 rounded-control bg-surface-sunken flex items-center justify-center shrink-0"><Gauge size={14} strokeWidth={2} className="text-ink-2" /></div>}
            title={t('sections.nozzles.title')}
            summary={t('sections.nozzles.summary', { used: pump.dus.length, max: pump.maxDuCount })}
            badgeTone={stepBadge(checklist, 'DISPENSERS')}
          >
            <NozzleContent pump={pump} onAdded={onPumpUpdated} />
          </AccordionSection>
        )}

        {/* ── Tanks — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="tanks"
            open={openSection === 'tanks'}
            onToggle={() => toggle('tanks')}
            icon={<div className="w-7 h-7 rounded-control bg-info-bg flex items-center justify-center shrink-0"><Database size={14} strokeWidth={2} className="text-primary" /></div>}
            title={t('sections.tanks.title')}
            summary={checklist?.items.find((item) => item.step === 'TANKS')?.detail
              ?? (pump.dus.length > 0 ? t('sections.tanks.tapToConfigure') : t('sections.tanks.addDuFirst'))}
            badgeTone={stepBadge(checklist, 'TANKS')}
          >
            <TanksContent pump={pump} />
          </AccordionSection>
        )}

        {/* ── Fuel Prices — all roles including MANAGER ── */}
        <AccordionSection
          sectionKey="prices"
          open={openSection === 'prices'}
          onToggle={() => toggle('prices')}
          icon={<div className="w-7 h-7 rounded-control bg-ok-bg flex items-center justify-center shrink-0"><IndianRupee size={14} strokeWidth={2} className="text-ok" /></div>}
          title={t('sections.prices.title')}
          summary={priceSummary}
          badgeTone={pricesStale ? NEEDS_ATTENTION_BADGE : COMPLETE_BADGE}
        >
          <FuelPricesContent pump={pump} currentPrices={currentPrices} />
        </AccordionSection>

        {/* ── Staff — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="staff"
            open={openSection === 'staff'}
            onToggle={() => toggle('staff')}
            icon={<div className="w-7 h-7 rounded-control bg-info-bg flex items-center justify-center shrink-0"><Users size={14} strokeWidth={2} className="text-primary" /></div>}
            title={t('sections.staff.title')}
            summary={staff.length > 0 ? t('sections.staff.members', { count: staff.length }) : t('sections.staff.noneAdded')}
            badgeTone={staff.length > 0 ? COMPLETE_BADGE : NEEDS_ATTENTION_BADGE}
          >
            <StaffContent pump={pump} />
          </AccordionSection>
        )}

        {/* ── Shift Definitions — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="shifts"
            open={openSection === 'shifts'}
            onToggle={() => toggle('shifts')}
            icon={<div className="w-7 h-7 rounded-control bg-info-bg flex items-center justify-center shrink-0"><Clock size={14} strokeWidth={2} className="text-primary" /></div>}
            title={t('sections.shifts.title')}
            summary={(() => {
              const groupCount = new Set(shiftDefs.map(d => `${d.effectiveFrom}|${d.effectiveTo ?? 'open'}`)).size
              return groupCount > 0 ? t('sections.shifts.schedules', { count: groupCount }) : t('sections.notConfigured')
            })()}
            badgeTone={shiftDefs.length > 0 ? COMPLETE_BADGE : NEEDS_ATTENTION_BADGE}
          >
            <ShiftDefinitionsContent pumpId={pump.id} />
          </AccordionSection>
        )}

        {/* ── Tankers — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="tankers"
            open={openSection === 'tankers'}
            onToggle={() => toggle('tankers')}
            icon={<div className="w-7 h-7 rounded-control bg-warn-bg flex items-center justify-center shrink-0"><Truck size={14} strokeWidth={2} className="text-warn" /></div>}
            title={t('sections.tankers.title')}
            summary={t('sections.tankers.summary')}
            badgeTone={NEUTRAL_BADGE}
          >
            <TankersContent pumpId={pump.id} />
          </AccordionSection>
        )}

        {/* ── Pump Settings — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="settings"
            open={openSection === 'settings'}
            onToggle={() => toggle('settings')}
            icon={<div className="w-7 h-7 rounded-control bg-warn-bg flex items-center justify-center shrink-0"><Settings size={14} strokeWidth={2} className="text-warn" /></div>}
            title={t('sections.settings.title')}
            summary={pump.discrepancyEscalationThreshold ? t('sections.settings.escalation', { amount: pump.discrepancyEscalationThreshold }) : t('sections.notConfigured')}
            badgeTone={pump.discrepancyEscalationThreshold ? COMPLETE_BADGE : NEUTRAL_BADGE}
          >
            <PumpSettingsContent pump={pump} onUpdated={onPumpUpdated} />
          </AccordionSection>
        )}

        {/* ── GST & Invoices — OWNER/ADMIN only ── */}
        {!isManager && (
          <AccordionSection
            sectionKey="gst"
            open={openSection === 'gst'}
            onToggle={() => toggle('gst')}
            icon={<div className="w-7 h-7 rounded-control bg-info-bg flex items-center justify-center shrink-0"><Receipt size={14} strokeWidth={2} className="text-primary" /></div>}
            title={t('sections.gst.title')}
            summary={pump.gstin ? t('sections.gst.summary', { gstin: pump.gstin }) : t('sections.notConfigured')}
            badgeTone={pump.gstin ? COMPLETE_BADGE : NEEDS_ATTENTION_BADGE}
          >
            <GstDetailsContent pump={pump} onUpdated={onPumpUpdated} />
          </AccordionSection>
        )}

      </div>
    </div>
  )
}
