package com.ppms.superadmin;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardOwnerRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void weakPasswordIsRejected() {
        assertThat(validator.validate(request("simple")))
                .extracting(ConstraintViolation::getPropertyPath)
                .extracting(Object::toString)
                .containsExactly("password");
    }

    @Test
    void strongPasswordIsAccepted() {
        assertThat(validator.validate(request("Owner@1234"))).isEmpty();
    }

    private static OnboardOwnerRequest request(String password) {
        OnboardOwnerRequest request = new OnboardOwnerRequest();
        request.setFullName("New Owner");
        request.setPhoneNumber("8123456789");
        request.setPassword(password);
        request.setPumpName("Fresh Pump");
        request.setPumpAddress("Highway 1");
        request.setMaxDuCount(2);
        return request;
    }
}
