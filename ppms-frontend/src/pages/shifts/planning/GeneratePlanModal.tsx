import { Modal } from '../../../components/Modal'
import type { ShiftPlanning } from './useShiftPlanning'

export function GeneratePlanModal({ form }: { form: ShiftPlanning }) {
  const { showGenerate, setShowGenerate, genDayOps, setGenDayOps, genNightOps, setGenNightOps, genError, generateMutation } = form
  if (!showGenerate) return null
  return (
    <Modal
      title="Auto-generate Schedule"
      subtitle="The system will assign operators based on preferences, leave dates, and fairness."
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
            {generateMutation.isPending ? 'Generating…' : 'Generate'}
          </button>
          <button onClick={() => setShowGenerate(false)}
            className="ui-btn ui-btn-secondary">
            Cancel
          </button>
        </>
      }
    >
      <div>
        <label className="block">
          <span className="ui-label">
            Operators per Morning / Evening shift
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
            Operators per Night shift (12 AM – 8 AM)
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
