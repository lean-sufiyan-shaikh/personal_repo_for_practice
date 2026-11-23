import type { CreditExtension } from '../../api/creditApi'

export function extensionSummary(ext: CreditExtension) {
  switch (ext.extensionType) {
    case 'AMOUNT_EXTENSION': return `+₹${ext.extensionAmount?.toLocaleString('en-IN')} credit headroom`
    case 'BILLING_CYCLE_EXTENSION': return 'Billing cycle overdue block deferred'
    case 'OVERDUE_BLOCK_WAIVER': return 'Overdue block waived'
  }
}
