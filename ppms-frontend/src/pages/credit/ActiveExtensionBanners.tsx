import type { CreditExtension } from '../../api/creditApi'
import { extensionSummary } from './extensionSummary'

interface ActiveExtensionBannersProps {
  extensions: CreditExtension[]
  canRevoke: boolean
  onRevoke: (extension: CreditExtension) => void
}

export function ActiveExtensionBanners({ extensions, canRevoke, onRevoke }: ActiveExtensionBannersProps) {
  return extensions.map((ext) => (
    <div key={ext.id} className="mt-3 ui-alert text-sm bg-blue-50 border-blue-200 flex items-center justify-between gap-3">
      <div className="flex items-center gap-2">
        <span className="font-medium text-blue-800">{extensionSummary(ext)}</span>
        <span className="text-xs text-blue-700">expires {ext.expiryDate}</span>
      </div>
      <div className="flex items-center gap-3 min-w-0">
        <span className="text-xs text-blue-700 italic truncate max-w-xs">{ext.reason}</span>
        {canRevoke && (
          <button type="button" onClick={() => onRevoke(ext)}
            className="ui-btn ui-btn-secondary ui-btn-compact text-red-700 border-red-200 hover:bg-red-50 shrink-0">
            Revoke
          </button>
        )}
      </div>
    </div>
  ))
}
