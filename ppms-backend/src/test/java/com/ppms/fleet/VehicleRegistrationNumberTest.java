package com.ppms.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleRegistrationNumberTest {

    @Test
    void normalisingUpperCasesAndDropsSpacesDashesDotsAndSlashes() {
        assertThat(VehicleRegistrationNumber.normalise(" mh-12 ab.1234 ")).isEqualTo("MH12AB1234");
        assertThat(VehicleRegistrationNumber.normalise("22 BH/1234-AA")).isEqualTo("22BH1234AA");
        assertThat(VehicleRegistrationNumber.normalise("  - ")).isNull();
        assertThat(VehicleRegistrationNumber.normalise(null)).isNull();
    }

    @Test
    void validationAcceptsStateBhAndOlderFormatsButNotJunk() {
        assertThat(VehicleRegistrationNumber.isValid("MH12AB1234")).isTrue();
        assertThat(VehicleRegistrationNumber.isValid("22BH1234AA")).isTrue();
        assertThat(VehicleRegistrationNumber.isValid("DL3C1234")).isTrue();
        assertThat(VehicleRegistrationNumber.isValid("ABCDEF")).isFalse();
        assertThat(VehicleRegistrationNumber.isValid("123456")).isFalse();
        assertThat(VehicleRegistrationNumber.isValid("M1")).isFalse();
        assertThat(VehicleRegistrationNumber.isValid("MH12AB12345678")).isFalse();
        assertThat(VehicleRegistrationNumber.isValid(null)).isFalse();
    }
}
