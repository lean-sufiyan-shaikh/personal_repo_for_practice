package com.ppms.pump;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.fuel.FuelType;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NozzleCalibrationControllerTest {

    private static final long PUMP_ID = 7L;
    private static final long NOZZLE_ID = 21L;
    private static final LocalDate CALIBRATED_ON = LocalDate.of(2026, 3, 10);

    @Mock private NozzleCalibrationLogRepository calibrationRepository;
    @Mock private NozzleRepository nozzleRepository;
    @Mock private DispensaryUnitRepository dispensaryUnitRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @InjectMocks private NozzleCalibrationController controller;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void nextDueDateOnOrBeforeTheCalibrationDateIsRefused() {
        when(nozzleRepository.findById(NOZZLE_ID)).thenReturn(Optional.of(
                Nozzle.builder().id(NOZZLE_ID).duId(11L).nozzleNumber(1).fuelType(FuelType.PETROL).build()));
        when(dispensaryUnitRepository.findById(11L)).thenReturn(Optional.of(
                DispensaryUnit.builder().id(11L).pumpId(PUMP_ID).build()));
        User owner = User.builder().id(3L).role(UserRole.OWNER).build();

        assertThatThrownBy(() -> controller.logCalibration(PUMP_ID, NOZZLE_ID, request(CALIBRATED_ON, "LM-118"), owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.CALIBRATION_NEXT_DUE_NOT_AFTER_DATE);
        assertThatThrownBy(() -> controller.logCalibration(PUMP_ID, NOZZLE_ID,
                request(CALIBRATED_ON.minusDays(1), "LM-118"), owner))
                .isInstanceOf(BusinessException.class);
        verify(calibrationRepository, never()).save(any());
    }

    @Test
    void nextDueDateAndCertificateReferenceAreRequired() {
        assertThat(validator.validate(request(null, "LM-118"))).hasSize(1);
        assertThat(validator.validate(request(CALIBRATED_ON.plusYears(1), null))).hasSize(1);
        assertThat(validator.validate(request(CALIBRATED_ON.plusYears(1), "LM-118"))).isEmpty();
    }

    private static LogCalibrationRequest request(LocalDate nextDue, String certificateReference) {
        return new LogCalibrationRequest(CALIBRATED_ON, nextDue, "Weights Dept", certificateReference, null);
    }
}
