package com.ppms.pump;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditClientRepository;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class PumpServiceTest {

    private static final long PUMP_ID = 7L;
    private static final long OWNER_ID = 3L;

    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private DispensaryUnitRepository duRepository;
    @Mock private NozzleRepository nozzleRepository;
    @Mock private UndergroundTankRepository tankRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreditClientRepository creditClientRepository;
    @Mock private TankerDeliveryRepository tankerDeliveryRepository;
    @Mock private AuditService auditService;
    @Mock private DispensaryUnitService dispensaryUnitService;

    @InjectMocks private PumpService pumpService;

    private final User owner = User.builder().id(OWNER_ID).role(UserRole.OWNER).status(UserStatus.ACTIVE).build();

    @Test
    void maxDuCountBelowTheDispensersAlreadyConfiguredIsRefused() {
        PumpLocation pump = pump();
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));
        when(duRepository.countByPumpId(PUMP_ID)).thenReturn(2L);

        assertThatThrownBy(() -> pumpService.updateMaxDuCount(PUMP_ID, 1, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.MAX_DU_COUNT_BELOW_CONFIGURED);
        assertThat(pump.getMaxDuCount()).isEqualTo(4);
    }

    @Test
    void maxDuCountEqualToOrAboveTheDispensersConfiguredIsSaved() {
        PumpLocation pump = pump();
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));
        when(duRepository.countByPumpId(PUMP_ID)).thenReturn(2L);

        pumpService.updateMaxDuCount(PUMP_ID, 2, owner);
        assertThat(pump.getMaxDuCount()).isEqualTo(2);
        pumpService.updateMaxDuCount(PUMP_ID, 6, owner);
        assertThat(pump.getMaxDuCount()).isEqualTo(6);
    }

    @Test
    void secondPumpWithTheSameNameForTheSameCreatorIsRefused() {
        CreatePumpRequest request = new CreatePumpRequest();
        request.setName("Highway");
        request.setAddress("NH 48");
        request.setMaxDuCount(4);
        when(pumpLocationRepository.existsByOwnerIdAndName(OWNER_ID, "Highway")).thenReturn(true);

        assertThatThrownBy(() -> pumpService.createPump(request, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.PUMP_NAME_TAKEN);
        verify(pumpLocationRepository, never()).save(any());
    }

    @Test
    void receiptToleranceAboveFivePercentOrNotAboveZeroIsRefused() {
        assertRefused(settings().receiptTolerancePercent(new BigDecimal("6")), ErrorCodes.RECEIPT_TOLERANCE_OUT_OF_RANGE);
        assertRefused(settings().receiptTolerancePercent(new BigDecimal("5.01")), ErrorCodes.RECEIPT_TOLERANCE_OUT_OF_RANGE);
        assertRefused(settings().receiptTolerancePercent(BigDecimal.ZERO), ErrorCodes.RECEIPT_TOLERANCE_OUT_OF_RANGE);
    }

    @Test
    void receiptToleranceUpToFivePercentAndDensityToleranceUpToFiftyAreSaved() {
        PumpLocation pump = savedWith(settings().receiptTolerancePercent(new BigDecimal("1"))
                .densityTolerance(new BigDecimal("5")));
        assertThat(pump.getReceiptTolerancePercent()).isEqualByComparingTo("1.00");
        assertThat(pump.getDensityTolerance()).isEqualByComparingTo("5.0");

        PumpLocation widest = savedWith(settings().receiptTolerancePercent(new BigDecimal("5"))
                .densityTolerance(new BigDecimal("50")));
        assertThat(widest.getReceiptTolerancePercent()).isEqualByComparingTo("5.00");
        assertThat(widest.getDensityTolerance()).isEqualByComparingTo("50.0");
    }

    @Test
    void densityToleranceAboveFiftyOrNotAboveZeroIsRefused() {
        assertRefused(settings().densityTolerance(new BigDecimal("50.1")), ErrorCodes.DENSITY_TOLERANCE_OUT_OF_RANGE);
        assertRefused(settings().densityTolerance(BigDecimal.ZERO), ErrorCodes.DENSITY_TOLERANCE_OUT_OF_RANGE);
    }

    @Test
    void otherToleranceSettingsOutsideTheirRangesAreRefused() {
        assertRefused(settings().earlyStartGraceMinutes(121), ErrorCodes.EARLY_START_GRACE_OUT_OF_RANGE);
        assertRefused(settings().earlyStartGraceMinutes(-1), ErrorCodes.EARLY_START_GRACE_OUT_OF_RANGE);
        assertRefused(settings().roundingTolerance(new BigDecimal("100.01")), ErrorCodes.ROUNDING_TOLERANCE_OUT_OF_RANGE);
        assertRefused(settings().roundingTolerance(new BigDecimal("-1")), ErrorCodes.ROUNDING_TOLERANCE_OUT_OF_RANGE);
        assertRefused(settings().testVolumeLitres(new BigDecimal("101")), ErrorCodes.TEST_VOLUME_OUT_OF_RANGE);
        assertRefused(settings().maxTestLitresPerNozzle(new BigDecimal("1001")), ErrorCodes.MAX_TEST_LITRES_OUT_OF_RANGE);
        assertRefused(settings().safeCountTolerance(new BigDecimal("10000.01")), ErrorCodes.SAFE_COUNT_TOLERANCE_OUT_OF_RANGE);
        assertRefused(settings().discrepancyEscalationThreshold(new BigDecimal("-1")), ErrorCodes.DISCREPANCY_THRESHOLD_NEGATIVE);
        assertRefused(settings().expenseApprovalThreshold(new BigDecimal("-1")), ErrorCodes.EXPENSE_APPROVAL_THRESHOLD_NEGATIVE);
    }

    @Test
    void zeroExpenseApprovalThresholdClearsIt() {
        PumpLocation pump = pump();
        pump.setExpenseApprovalThreshold(new BigDecimal("500.00"));
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));

        pumpService.updatePumpSettings(PUMP_ID, settings().expenseApprovalThreshold(BigDecimal.ZERO).build(), owner);

        assertThat(pump.getExpenseApprovalThreshold()).isNull();
    }

    @Test
    void changedEscalationThresholdReDecidesPendingDiscrepancies() {
        PumpLocation pump = pump();
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));

        pumpService.updatePumpSettings(PUMP_ID, settings().discrepancyEscalationThreshold(new BigDecimal("250")).build(), owner);

        assertThat(pump.getDiscrepancyEscalationThreshold()).isEqualByComparingTo("250.00");
        verify(shiftRepository).reEscalatePendingDiscrepancies(PUMP_ID, new BigDecimal("250.00"));
    }

    @Test
    void pumpOfAnotherOwnerIsNotFoundForDeletion() {
        PumpLocation pump = pump();
        pump.setOwnerId(99L);
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));

        assertThatThrownBy(() -> pumpService.deletePump(PUMP_ID, owner)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pumpWithShiftsIsNotDeleted() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump()));
        when(shiftRepository.existsByPumpId(PUMP_ID)).thenReturn(true);

        assertDeleteRefused(ErrorCodes.PUMP_HAS_SHIFTS);
    }

    @Test
    void pumpWithActiveStaffIsNotDeleted() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump()));
        when(userRepository.existsByAssignedPumpIdAndStatus(PUMP_ID, UserStatus.ACTIVE)).thenReturn(true);

        assertDeleteRefused(ErrorCodes.PUMP_HAS_ACTIVE_STAFF);
    }

    @Test
    void pumpWithOnlyDeactivatedStaffIsNotDeleted() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump()));
        when(userRepository.existsByAssignedPumpId(PUMP_ID)).thenReturn(true);

        assertDeleteRefused(ErrorCodes.PUMP_HAS_DEACTIVATED_STAFF);
    }

    @Test
    void pumpWithCreditClientsOrDeliveriesIsNotDeleted() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump()));
        when(creditClientRepository.existsByPumpId(PUMP_ID)).thenReturn(true);
        assertDeleteRefused(ErrorCodes.PUMP_HAS_RECORDED_DATA);

        when(creditClientRepository.existsByPumpId(PUMP_ID)).thenReturn(false);
        when(tankerDeliveryRepository.existsByPumpId(PUMP_ID)).thenReturn(true);
        assertDeleteRefused(ErrorCodes.PUMP_HAS_RECORDED_DATA);
    }

    private void assertDeleteRefused(String code) {
        assertThatThrownBy(() -> pumpService.deletePump(PUMP_ID, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(code);
        verify(pumpLocationRepository, never()).delete(any());
    }

    private void assertRefused(SettingsBuilder settings, String code) {
        PumpLocation pump = pump();
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));

        assertThatThrownBy(() -> pumpService.updatePumpSettings(PUMP_ID, settings.build(), owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(code);
        verify(pumpLocationRepository, never()).save(any());
    }

    private PumpLocation savedWith(SettingsBuilder settings) {
        PumpLocation pump = pump();
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));
        pumpService.updatePumpSettings(PUMP_ID, settings.build(), owner);
        return pump;
    }

    private static PumpLocation pump() {
        return PumpLocation.builder().id(PUMP_ID).ownerId(OWNER_ID).name("Main").address("NH 48").maxDuCount(4).build();
    }

    private static SettingsBuilder settings() {
        return new SettingsBuilder();
    }

    private static final class SettingsBuilder {
        private BigDecimal discrepancyEscalationThreshold;
        private BigDecimal expenseApprovalThreshold;
        private BigDecimal receiptTolerancePercent;
        private BigDecimal densityTolerance;
        private BigDecimal safeCountTolerance;
        private Integer earlyStartGraceMinutes;
        private BigDecimal roundingTolerance;
        private BigDecimal testVolumeLitres;
        private BigDecimal maxTestLitresPerNozzle;

        SettingsBuilder discrepancyEscalationThreshold(BigDecimal value) {
            discrepancyEscalationThreshold = value;
            return this;
        }

        SettingsBuilder expenseApprovalThreshold(BigDecimal value) {
            expenseApprovalThreshold = value;
            return this;
        }

        SettingsBuilder receiptTolerancePercent(BigDecimal value) {
            receiptTolerancePercent = value;
            return this;
        }

        SettingsBuilder densityTolerance(BigDecimal value) {
            densityTolerance = value;
            return this;
        }

        SettingsBuilder safeCountTolerance(BigDecimal value) {
            safeCountTolerance = value;
            return this;
        }

        SettingsBuilder earlyStartGraceMinutes(Integer value) {
            earlyStartGraceMinutes = value;
            return this;
        }

        SettingsBuilder roundingTolerance(BigDecimal value) {
            roundingTolerance = value;
            return this;
        }

        SettingsBuilder testVolumeLitres(BigDecimal value) {
            testVolumeLitres = value;
            return this;
        }

        SettingsBuilder maxTestLitresPerNozzle(BigDecimal value) {
            maxTestLitresPerNozzle = value;
            return this;
        }

        UpdatePumpSettingsRequest build() {
            return new UpdatePumpSettingsRequest(discrepancyEscalationThreshold, expenseApprovalThreshold, null, null,
                    null, null, receiptTolerancePercent, densityTolerance, safeCountTolerance, null,
                    earlyStartGraceMinutes, roundingTolerance, testVolumeLitres, maxTestLitresPerNozzle, null);
        }
    }
}
