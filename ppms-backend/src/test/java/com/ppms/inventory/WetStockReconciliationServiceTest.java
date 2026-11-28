package com.ppms.inventory;

import com.ppms.adjustment.FuelDipEntryRepository;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.shift.ShiftFuelReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WetStockReconciliationServiceTest {

    private static final Long PUMP_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Mock private UndergroundTankRepository tankRepository;
    @Mock private DipCheckRepository dipCheckRepository;
    @Mock private DipCheckNozzleReadingRepository dipSnapshotRepository;
    @Mock private TankerDeliveryRepository deliveryRepository;
    @Mock private ShiftFuelReadingRepository fuelReadingRepository;
    @Mock private FuelDipEntryRepository fuelDipEntryRepository;
    @Mock private BusinessClock businessClock;
    @InjectMocks private WetStockReconciliationService reconciliationService;

    @BeforeEach
    void todayInTheBusinessZone() {
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.zone()).thenReturn(ZONE);
        when(businessClock.startOfDay(any())).thenAnswer(invocation ->
                invocation.<LocalDate>getArgument(0).atStartOfDay(ZONE).toOffsetDateTime());
        when(tankRepository.findByPumpId(PUMP_ID)).thenReturn(List.of());
    }

    @Test
    void rangeStartingAfterItEndsIsRefused() {
        assertRefusedWith(TODAY, TODAY.minusDays(1), "DATE_RANGE_START_AFTER_END");
    }

    @Test
    void rangeEndingInTheFutureIsRefused() {
        assertRefusedWith(TODAY, TODAY.plusDays(1), "DATE_RANGE_END_IN_FUTURE");
    }

    @Test
    void rangeOfNinetyTwoDaysIsAllowedAndOneDayMoreIsRefused() {
        assertThat(reconciliationService.reconcile(PUMP_ID, TODAY.minusDays(91), TODAY, null).tanks()).isEmpty();
        assertRefusedWith(TODAY.minusDays(92), TODAY, "DATE_RANGE_TOO_LONG");
    }

    @Test
    void tankThatIsNotOnThePumpIsNotFound() {
        assertThatThrownBy(() -> reconciliationService.reconcile(PUMP_ID, TODAY.minusDays(1), TODAY, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void assertRefusedWith(LocalDate from, LocalDate to, String code) {
        assertThatThrownBy(() -> reconciliationService.reconcile(PUMP_ID, from, to, null))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> assertThat(refusal.getCode()).isEqualTo(code));
    }
}
