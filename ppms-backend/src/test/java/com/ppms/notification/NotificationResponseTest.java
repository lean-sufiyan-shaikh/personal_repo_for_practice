package com.ppms.notification;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationResponseTest {

    @Test
    void notificationCarriesThePageItOpensAndTheCodeAndParamsItsTextWasBuiltFrom() {
        NotificationResponse zeroSale = NotificationResponse.of(notification(NotificationType.ZERO_SALE_SHIFT,
                "ZERO_SALE_SHIFT:shift:4242", Map.of("shiftName", "Morning", "shiftId", 4242)), false);

        assertThat(zeroSale.target()).isEqualTo(new NotificationTarget("/dashboard/shifts", Map.of("shift", "4242")));
        assertThat(zeroSale.code()).isEqualTo("ZERO_SALE_SHIFT");
        assertThat(zeroSale.read()).isFalse();
        assertThat(zeroSale.createdAt()).isNotNull();
    }

    @Test
    void expiredDocumentHasItsOwnCodeAndKeepsTheEnglishMessage() {
        NotificationResponse expired = NotificationResponse.of(notification(NotificationType.DOCUMENT_EXPIRING,
                "DOCUMENT_EXPIRING:doc:77:2026-01-01:EXPIRED",
                Map.of("documentName", "Licence", "expiryDate", "2026-01-01")), true);
        NotificationResponse expiringSoon = NotificationResponse.of(notification(NotificationType.DOCUMENT_EXPIRING,
                "DOCUMENT_EXPIRING:doc:77:2026-01-01:SOON", Map.of("documentName", "Licence")), false);

        assertThat(expired.code()).isEqualTo("DOCUMENT_EXPIRED");
        assertThat(expired.params()).containsEntry("documentName", "Licence").containsEntry("expiryDate", "2026-01-01");
        assertThat(expired.message()).isEqualTo("English message");
        assertThat(expired.read()).isTrue();
        assertThat(expiringSoon.code()).isEqualTo("DOCUMENT_EXPIRING");
    }

    @Test
    void notificationStoredWithoutParamsHasNoCode() {
        NotificationResponse old = NotificationResponse.of(notification(NotificationType.LOW_STOCK, "LOW_STOCK:tank:1", null), false);

        assertThat(old.code()).isNull();
        assertThat(old.params()).isNull();
    }

    private static Notification notification(NotificationType type, String dedupKey, Map<String, Object> params) {
        return Notification.builder().id(1L).pumpId(2L).type(type).title("Title").message("English message")
                .dedupKey(dedupKey).params(params).createdAt(OffsetDateTime.parse("2026-11-27T10:00:00+05:30")).build();
    }
}
