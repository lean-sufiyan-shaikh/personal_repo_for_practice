interface SuspendedPumpNoticeProps {
  canSwitchPump: boolean
  isStaff: boolean
}

export function SuspendedPumpNotice({ canSwitchPump, isStaff }: SuspendedPumpNoticeProps) {
  if (isStaff && !canSwitchPump) {
    return (
      <div className="p-6">
        <div role="alert" className="ui-card max-w-xl mx-auto text-center space-y-2">
          <h1 className="text-lg font-bold text-ink">Your pump is suspended</h1>
          <p className="text-sm text-ink-2">
            Its data cannot be viewed or changed until the pump is reactivated. Please contact the pump owner.
          </p>
        </div>
      </div>
    )
  }
  return (
    <div className="p-6">
      <div role="alert" className="ui-card max-w-xl mx-auto text-center space-y-2">
        <h1 className="text-lg font-bold text-ink">This pump is suspended</h1>
        <p className="text-sm text-ink-2">
          Its data cannot be viewed or changed until the pump is reactivated.
          {canSwitchPump
            ? ' Choose another pump at the top of the page, or contact PPMS support to reactivate this one.'
            : ' Please contact the pump owner or PPMS support.'}
        </p>
      </div>
    </div>
  )
}
