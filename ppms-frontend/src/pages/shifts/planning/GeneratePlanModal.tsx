import { useTranslation } from 'react-i18next'
import { Modal } from '../../../components/Modal'
import type { ShiftPlanning } from './useShiftPlanning'

export function GeneratePlanModal({ form }: { form: ShiftPlanning }) {
  const { t } = useTranslation(['shifts', 'common'])
  const { showGenerate, setShowGenerate, genDayOps, setGenDayOps, genNightOps, setGenNightOps, genError, generateMutation } = form
  if (!showGenerate) return null
  return (
    <Modal
      title={t('planning.generate.title')}
      subtitle={t('planning.generate.subtitle')}
      tone="info"
      size="sm"
      onClose={() => setShowGenerate(false)}
      bodyClassName="space-y-3"
      footer={
        <>
          <button
            onClick={() => generateMutation.mutate()}
            disabled={generateMutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {generateMutation.isPending ? t('planning.generate.generating') : t('planning.generate.submit')}
          </button>
          <button onClick={() => setShowGenerate(false)}
            className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
        </>
      }
    >
      <div>
        <label className="block">
          <span className="ui-label">
            {t('planning.generate.dayOperators')}
          </span>
          <input type="number" min="1" max="20" value={genDayOps}
            onChange={e => setGenDayOps(e.target.value)}
            className="w-24 text-sm"
          />
        </label>
      </div>
      <div>
        <label className="block">
          <span className="ui-label">
            {t('planning.generate.nightOperators')}
          </span>
          <input type="number" min="1" max="20" value={genNightOps}
            onChange={e => setGenNightOps(e.target.value)}
            className="w-24 text-sm"
          />
        </label>
      </div>
      {genError && <p role="alert" className="ui-error-text">{genError}</p>}
    </Modal>
  )
}
