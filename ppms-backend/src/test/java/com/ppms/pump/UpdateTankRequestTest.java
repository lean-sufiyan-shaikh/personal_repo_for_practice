package com.ppms.pump;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateTankRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void lossAlertLimitOutsideOneHundredthToOneHundredPercentIsRefused() {
        assertThat(validator.validate(request("0"))).hasSize(1);
        assertThat(validator.validate(request("101"))).hasSize(1);
        assertThat(validator.validate(request("0.005"))).isNotEmpty();
    }

    @Test
    void lossAlertLimitWithinRangeOrLeftOutIsAccepted() {
        assertThat(validator.validate(request("0.01"))).isEmpty();
        assertThat(validator.validate(request("100"))).isEmpty();
        assertThat(validator.validate(request(null))).isEmpty();
    }

    private static UpdateTankRequest request(String lossAlertPercent) {
        UpdateTankRequest request = new UpdateTankRequest();
        request.setCapacity(new BigDecimal("20000"));
        request.setLossAlertPercent(lossAlertPercent != null ? new BigDecimal(lossAlertPercent) : null);
        return request;
    }
}
