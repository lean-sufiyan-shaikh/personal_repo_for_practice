package com.ppms.adjustment;

import com.ppms.common.exception.BusinessException;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.fuel.FuelType;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NozzleAdjustmentServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long NOZZLE_ID = 11L;
    private static final long ADJUSTMENT_ID = 60L;

    @Mock private NozzleReadingAdjustmentRepository adjustmentRepository;
    @Mock private NozzleRepository nozzleRepository;
    @Mock private DispensaryUnitRepository dispensaryUnitRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private ClosedDayGuard closedDayGuard;

    private NozzleAdjustmentService adjustmentService;
    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();
    private final User manager = User.builder().id(2L).role(UserRole.MANAGER).build();

    @BeforeEach
    void createService() {
        NozzleAdjustmentSupportService supportService =
                new NozzleAdjustmentSupportService(nozzleRepository, dispensaryUnitRepository, shiftRepository);
        adjustmentService = new NozzleAdjustmentService(adjustmentRepository, null, supportService, null, null, null,
                null, null, closedDayGuard, null, null);
    }

    @Test
    void meterCannotBeAdjustedWhileAShiftRunsOnTheNozzle() {
        givenNozzleAt("1000.000");
        when(shiftRepository.findOpenShiftByNozzle(NOZZLE_ID)).thenReturn(Optional.of(Shift.builder().id(30L).build()));

        assertRecordRefused(owner, "1050.000", "METER_ADJUSTMENT_SHIFT_RUNNING");
    }

    @Test
    void secondForwardRequestIsRefusedWhileOneWaitsForApproval() {
        givenNozzleAt("1000.000");
        when(adjustmentRepository.existsByNozzleIdAndStatus(NOZZLE_ID, MeterAdjustmentStatus.PENDING_APPROVAL)).thenReturn(true);

        assertRecordRefused(manager, "1060.000", "METER_ADJUSTMENT_ALREADY_PENDING");
    }

    @Test
    void managerCannotMoveTheMeterBack() {
        givenNozzleAt("1000.000");

        assertRecordRefused(manager, "900.000", "ADJUSTMENT_OWNER_OR_ADMIN_ONLY");
    }

    @Test
    void adjustmentAlreadyDecidedCannotBeDecidedAgain() {
        givenRequest(MeterAdjustmentStatus.REJECTED);

        assertReviewRefused("METER_ADJUSTMENT_NOT_PENDING");
    }

    @Test
    void approvalIsRefusedOnceTheNozzlesReadingHasMoved() {
        givenRequest(MeterAdjustmentStatus.PENDING_APPROVAL);
        givenNozzleAt("1010.000");

        assertThatThrownBy(() -> adjustmentService.review(PUMP_ID, ADJUSTMENT_ID, ExpenseApprovalStatus.APPROVED, null, owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("METER_ADJUSTMENT_READING_MOVED");
                    assertThat(refused.getParams()).containsEntry("currentReading", "1010.000");
                });
    }

    private void givenNozzleAt(String reading) {
        when(nozzleRepository.findByIdForUpdate(NOZZLE_ID)).thenReturn(Optional.of(Nozzle.builder().id(NOZZLE_ID).duId(3L)
                .fuelType(FuelType.PETROL).lastReading(new BigDecimal(reading)).build()));
    }

    private void givenRequest(MeterAdjustmentStatus status) {
        when(adjustmentRepository.findByIdForUpdate(ADJUSTMENT_ID)).thenReturn(Optional.of(NozzleReadingAdjustment.builder()
                .id(ADJUSTMENT_ID).pumpId(PUMP_ID).nozzleId(NOZZLE_ID).previousReading(new BigDecimal("1000.000"))
                .newReading(new BigDecimal("1050.000")).status(status).build()));
    }

    private void assertRecordRefused(User user, String newReading, String code) {
        RecordAdjustmentRequest request = new RecordAdjustmentRequest();
        request.setAdjustmentType("CUSTOM_READING");
        request.setNewReading(new BigDecimal(newReading));
        request.setReason("Repair");
        assertThatThrownBy(() -> adjustmentService.recordReadingAdjustment(PUMP_ID, NOZZLE_ID, request, user))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(adjustmentRepository, never()).save(any());
    }

    private void assertReviewRefused(String code) {
        assertThatThrownBy(() -> adjustmentService.review(PUMP_ID, ADJUSTMENT_ID, ExpenseApprovalStatus.APPROVED, null, owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
    }
}
