package com.ppms.notification;

import java.time.OffsetDateTime;

/** A notification as the signed-in user sees it: whether they have read it, and the page it opens. */
public record NotificationResponse(
        Long id,
        Long pumpId,
        NotificationType type,
        String title,
        String message,
        OffsetDateTime createdAt,
        boolean read,
        NotificationTarget target
) {

    static NotificationResponse of(Notification notification, boolean read) {
        return new NotificationResponse(notification.getId(), notification.getPumpId(), notification.getType(),
                notification.getTitle(), notification.getMessage(), notification.getCreatedAt(), read,
                NotificationTargets.targetOf(notification.getType(), notification.getDedupKey()));
    }
}
