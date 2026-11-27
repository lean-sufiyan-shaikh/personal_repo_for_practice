import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ImportType } from '../../../api/importApi'
import { useAuthStore } from '../../../store/authStore'
import { DemoPumpPanel } from './DemoPumpPanel'
import { ImportFlow } from './ImportFlow'
import { IMPORT_TEMPLATES, IMPORT_TYPES } from './importTemplates'

/** Setup's import area: pick what to bring in from Excel, then follow the steps; owners can also try a demo pump. */
export function ImportDataContent({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('setup')
  const isOwner = useAuthStore((state) => state.user?.role === 'OWNER')
  const [type, setType] = useState<ImportType>('staff')
  const groupLabelId = useId()

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <p id={groupLabelId} className="text-sm font-semibold text-ink">{t('import.typeLabel')}</p>
        <div role="group" aria-labelledby={groupLabelId} className="flex flex-wrap gap-2">
          {IMPORT_TYPES.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={type === option}
              onClick={() => setType(option)}
              className={`ui-btn ui-btn-compact ${type === option ? 'ui-btn-primary' : 'ui-btn-secondary'}`}
            >
              {t(`import.types.${option}`)}
            </button>
          ))}
        </div>
        <p className="ui-help">{t(`import.typeHelp.${type}`)}</p>
      </div>

      <ImportFlow key={type} pumpId={pumpId} template={IMPORT_TEMPLATES[type]} />

      {isOwner && <DemoPumpPanel />}
    </div>
  )
}
