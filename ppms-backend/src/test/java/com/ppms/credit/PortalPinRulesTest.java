package com.ppms.credit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PortalPinRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"000000", "777777", "012345", "123456", "456789", "987654", "543210", "121212", "123123",
            "112233", "147258", "696969"})
    void easyToGuessPinsAreTrivial(String pin) {
        assertThat(PortalPinRules.isTrivial(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"482913", "582614", "730194", "205867"})
    void otherSixDigitPinsAreStrong(String pin) {
        assertThat(PortalPinRules.isTrivial(pin)).isFalse();
        assertThat(PortalPinRules.isWeak(pin)).isFalse();
    }

    @Test
    void pinShorterThanSixDigitsIsWeakEvenWhenNotTrivial() {
        assertThat(PortalPinRules.isWeak("4821")).isTrue();
        assertThat(PortalPinRules.isWeak("48291")).isTrue();
    }

    @Test
    void lockoutDoublesFromFifteenMinutesAndStopsAtADay() {
        assertThat(CreditPortalAttemptService.lockDuration(1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(CreditPortalAttemptService.lockDuration(2)).isEqualTo(Duration.ofMinutes(30));
        assertThat(CreditPortalAttemptService.lockDuration(4)).isEqualTo(Duration.ofHours(2));
        assertThat(CreditPortalAttemptService.lockDuration(7)).isEqualTo(Duration.ofHours(16));
        assertThat(CreditPortalAttemptService.lockDuration(8)).isEqualTo(Duration.ofHours(24));
        assertThat(CreditPortalAttemptService.lockDuration(50)).isEqualTo(Duration.ofHours(24));
    }
}
