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
  onOpenStatement: () => void
}

export function ClientActions({
  canManageInterest, canApplyInterest, isApplyingInterest, canGrantExtension, canRecordPayment,
  onOpenInterestSettings, onApplyInterest, onGrantExtension, onRecordPayment, onOpenStatement,
}: ClientActionsProps) {
  return (
    <div className="flex items-center gap-2 flex-wrap">
      <button type="button" onClick={onOpenStatement} className="ui-btn ui-btn-secondary">
        Statement
      </button>
      {canManageInterest && (
        <button onClick={onOpenInterestSettings} className="ui-btn ui-btn-secondary text-info border-info/30 hover:bg-info-bg">
          Interest Settings
        </button>
      )}
      {canApplyInterest && (
        <button onClick={onApplyInterest} disabled={isApplyingInterest}
          className="ui-btn ui-btn-secondary">
          {isApplyingInterest ? 'Applying…' : 'Apply Interest'}
        </button>
      )}
      {canGrantExtension && (
        <button onClick={onGrantExtension} className="ui-btn ui-btn-secondary text-info border-info/30 hover:bg-info-bg">
          Grant Extension
        </button>
      )}
      {canRecordPayment && (
        <button onClick={onRecordPayment} className="ui-btn ui-btn-primary">
          Record Payment
        </button>
      )}
    </div>
  )
}
