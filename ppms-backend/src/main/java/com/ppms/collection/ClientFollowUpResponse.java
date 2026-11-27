package com.ppms.collection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Where collecting from one account stands. A sub-account is followed up on its parent, whose figures cover the group.
 *
 * @param overdueSince   first overdue day of the running episode; null when current
 * @param reachedStage   latest reminder stage reached; null before the first
 * @param creditHoldDays days overdue at which credit is held; null when the pump holds no credit
 * @param onHold         whether credit is held now, after any override
 */
public record ClientFollowUpResponse(Long clientId, BigDecimal outstanding, long daysOverdue, LocalDate overdueSince,
                                     Integer reachedStage, boolean reminderDue, Integer creditHoldDays, boolean onHold,
                                     String upiPayLink, List<PromiseResponse> promises,
                                     List<ReminderResponse> reminders) {
}
