import { formatIstDate } from '../../utils/date'
import { formatCurrency } from '../../utils/format'

/** The short WhatsApp message sent with a statement: who it is for, the period and what is owed. */
export function statementMessage(clientName: string, fromDate: string, toDate: string, outstanding: number) {
  return `Namaste ${clientName}, your credit statement for ${formatIstDate(fromDate)} to ${formatIstDate(toDate)} is ready. `
    + `Outstanding balance today: ${formatCurrency(outstanding)}. Please contact us to settle or to get the printed statement.`
}
