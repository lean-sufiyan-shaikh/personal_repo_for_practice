package com.ppms.credit;

import com.ppms.user.User;

/**
 * An Owner or Admin allowing shift-close credit for accounts that are over their limit, on hold or overdue.
 *
 * @param shiftId null for a backfilled shift that has no id yet
 */
public record CloseCreditOverride(Long shiftId, String reason, User allowedBy) {
}
