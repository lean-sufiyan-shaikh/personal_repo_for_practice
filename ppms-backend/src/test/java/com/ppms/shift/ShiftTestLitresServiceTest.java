package com.ppms.shift;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.fuel.FuelType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftTestLitresServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;

    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;
    @InjectMocks private ShiftTestLitresService testLitresService;

    private final User manager = User.builder().id(2L).build();

    @Test
    void testLitresAboveTheLitresTheMeterDispensedAreRefused() {
        assertThatThrownBy(() -> ShiftTestLitresService.validTestLitres(reading("0"), new BigDecimal("30"), new BigDecimal("25")))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("TEST_LITRES_ABOVE_METER_LITRES"));
    }

    @Test
    void testLitresUpToTheMeterLitresAreKeptToTwoDecimals() {
        assertThat(ShiftTestLitresService.validTestLitres(reading("0"), new BigDecimal("5.005"), new BigDecimal("25")))
                .isEqualTo(new BigDecimal("5.01"));
    }

    @Test
    void testLitresAboveThePumpLimitNeedANote() {
        givenLimit("10");

        assertThatThrownBy(() -> testLitresService.requireNoteAndAuditAboveMax(PUMP_ID, SHIFT_ID, List.of(reading("15")), " ", manager))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("TEST_LITRES_NOTE_REQUIRED"));
    }

    @Test
    void testLitresAboveThePumpLimitWithANoteAreAuditedWithTheNote() {
        givenLimit("10");

        testLitresService.requireNoteAndAuditAboveMax(PUMP_ID, SHIFT_ID, List.of(reading("15")),
                "Calibration redone after a nozzle change", manager);

        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.TEST_LITRES_ABOVE_MAX), eq("Shift"), eq("30"),
                contains("Calibration redone after a nozzle change"), eq(manager));
    }

    @Test
    void testLitresAtThePumpLimitNeedNoNote() {
        givenLimit("10");

        testLitresService.requireNoteAndAuditAboveMax(PUMP_ID, SHIFT_ID, List.of(reading("10")), null, manager);

        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    private void givenLimit(String litres) {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(
                PumpLocation.builder().id(PUMP_ID).maxTestLitresPerNozzle(new BigDecimal(litres)).build()));
    }

    private static ShiftFuelReading reading(String testLitres) {
        return ShiftFuelReading.builder().shiftId(SHIFT_ID).nozzleId(11L).fuelType(FuelType.PETROL)
                .startReading(new BigDecimal("1000.000")).priceSnapshot(new BigDecimal("100.00"))
                .testLitres(new BigDecimal(testLitres)).build();
    }
}
