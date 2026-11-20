interface ClientActionsProps {
  canManageInterest: boolean
  canApplyInterest: boolean
  isApplyingInterest: boolean
  canGrantExtension: boolean
  canRecordPayment: boolean
  onOpenInterestSettings: () => void
  onApplyInterest: () => void
  onGrantExtension: () => void
  onRecordPayment: () => void
}

export function ClientActions({
  canManageInterest, canApplyInterest, isApplyingInterest, canGrantExtension, canRecordPayment,
  onOpenInterestSettings, onApplyInterest, onGrantExtension, onRecordPayment,
}: ClientActionsProps) {
  return (
    <div className="flex items-center gap-2 flex-wrap">
      {canManageInterest && (
        <button onClick={onOpenInterestSettings} className="ui-btn ui-btn-secondary text-purple-700 border-purple-200 hover:bg-purple-50">
          Interest Settings
        </button>
      )}
      {canApplyInterest && (
        <button onClick={onApplyInterest} disabled={isApplyingInterest}
          className="ui-btn text-white bg-purple-600 hover:bg-purple-700 disabled:opacity-50">
          {isApplyingInterest ? 'Applying…' : 'Apply Interest'}
        </button>
      )}
      {canGrantExtension && (
        <button onClick={onGrantExtension} className="ui-btn ui-btn-secondary text-blue-700 border-blue-200 hover:bg-blue-50">
          Grant Extension
        </button>
      )}
      {canRecordPayment && (
        <button onClick={onRecordPayment} className="ui-btn text-white bg-green-600 hover:bg-green-700">
          Record Payment
        </button>
      )}
    </div>
  )
}
