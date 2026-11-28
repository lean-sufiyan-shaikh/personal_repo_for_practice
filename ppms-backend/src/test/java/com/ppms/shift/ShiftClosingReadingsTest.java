package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.credit.CreditAccountPolicyService;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditInterestService;
import com.ppms.fleet.VehicleCreditCheck;
import com.ppms.fleet.VehicleCreditDecision;
import com.ppms.fuel.FuelType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.ppms.user.User;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftClosingReadingsTest {

    private static final Long NOZZLE_ID = 11L;
    private static final BigDecimal MAX_METER = new BigDecimal("999999.999");

    @Mock private ShiftFuelReadingRepository fuelReadingRepository;
    @Mock private ShiftCreditEntryRepository creditEntryRepository;
    @Mock private ShiftPriceSegmentService priceSegmentService;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private CreditAccountPolicyService creditAccountPolicyService;
    @Mock private VehicleCreditCheck vehicleCreditCheck;
    @Mock private CreditInterestService creditInterestService;
    @InjectMocks private ShiftLifecycleSupportService lifecycleSupportService;

    @Test
    void closingReadingJustBelowOpeningIsRejectedInsteadOfCountedAsRollover() {
        ShiftFuelReading reading = readingStartingAt("5000.000");

        assertThatThrownBy(() -> lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, MAX_METER), closeRequest("4999.000", "0.00"), BigDecimal.ZERO, List.of(reading)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("is below the opening reading");
    }

    @Test
    void meterAtTheTopOfItsRangeRollsOverToZero() {
        ShiftFuelReading reading = readingStartingAt("999990.000");

        ShiftLifecycleSupportService.ClosingSummary summary = lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, MAX_METER), closeRequest("5.001", "1500.00"), BigDecimal.ZERO, List.of(reading));

        assertThat(reading.getUnitsSold()).isEqualByComparingTo("15.000");
        assertThat(summary.getTotalDue()).isEqualByComparingTo("1500.00");
    }

    @Test
    void confirmedDropsCountAsCollectedCashSoOnlyTheBagIsCompared() {
        ShiftFuelReading reading = readingStartingAt("5000.000");

        ShiftLifecycleSupportService.ClosingSummary summary = lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, MAX_METER), closeRequest("5010.000", "400.00"), new BigDecimal("600.00"), List.of(reading));

        assertThat(summary.getTotalDue()).isEqualByComparingTo("1000.00");
        assertThat(summary.getTotalCollected()).isEqualByComparingTo("1000.00");
        assertThat(summary.getDiscrepancyType()).isNull();
        assertThat(summary.getStatus()).isEqualTo(ShiftStatus.CLOSED_BALANCED);
    }

    @Test
    void rolloverIsAcceptedWhenTheOpeningReadingIsExactlyNinetyNinePercentOfTheMeterRange() {
        BigDecimal roundMaxMeter = new BigDecimal("1000000.000");
        ShiftFuelReading reading = readingStartingAt("990000.000");

        lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, roundMaxMeter), closeRequest("5.000", "1000500.00"), BigDecimal.ZERO, List.of(reading));

        assertThat(reading.getUnitsSold()).isEqualByComparingTo("10005.000");
    }

    @Test
    void rolloverIsRefusedWhenTheOpeningReadingIsJustUnderNinetyNinePercentOfTheMeterRange() {
        BigDecimal roundMaxMeter = new BigDecimal("1000000.000");
        ShiftFuelReading reading = readingStartingAt("989999.999");

        assertThatThrownBy(() -> lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, roundMaxMeter), closeRequest("5.000", "0.00"), BigDecimal.ZERO, List.of(reading)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("is below the opening reading");
    }

    @Test
    void differenceWithinTheRoundingToleranceClosesBalancedAndIsKeptAsRounding() {
        ShiftFuelReading reading = readingStartingAt("5000.000");

        ShiftLifecycleSupportService.ClosingSummary summary = lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, MAX_METER), closeRequest("5010.005", "999.50"), BigDecimal.ZERO, List.of(reading));

        assertThat(summary.getTotalDue()).isEqualByComparingTo("1000.50");
        assertThat(summary.getStatus()).isEqualTo(ShiftStatus.CLOSED_BALANCED);
        assertThat(summary.getDiscrepancyType()).isNull();
        assertThat(summary.getDiscrepancyAmount()).isEqualByComparingTo("0");
        assertThat(summary.getRoundingAdjustment()).isEqualByComparingTo("-1.00");
    }

    @Test
    void differenceBeyondTheRoundingToleranceIsAPendingDiscrepancy() {
        ShiftFuelReading reading = readingStartingAt("5000.000");

        ShiftLifecycleSupportService.ClosingSummary summary = lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, MAX_METER), closeRequest("5010.000", "998.99"), BigDecimal.ZERO, List.of(reading));

        assertThat(summary.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(summary.getDiscrepancyType()).isEqualTo(DiscrepancyType.SHORT);
        assertThat(summary.getDiscrepancyAmount()).isEqualByComparingTo("1.01");
        assertThat(summary.getRoundingAdjustment()).isNull();
    }

    @Test
    void theSameNozzleTwiceInOneRequestIsRejected() {
        CloseShiftRequest request = closeRequest("5010.000", "1000.00");
        request.setFuelReadings(List.of(
                new CloseShiftRequest.OutletEndReadingRequest(NOZZLE_ID, new BigDecimal("5010.000")),
                new CloseShiftRequest.OutletEndReadingRequest(NOZZLE_ID, new BigDecimal("5020.000"))));

        assertThatThrownBy(() -> lifecycleSupportService.processClosingReadings(
                1L, Map.of(NOZZLE_ID, MAX_METER), request, BigDecimal.ZERO, List.of(readingStartingAt("5000.000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only appear once");
    }

    @Test
    void zeroCreditTotalIsRejectedWhileAnEntryRecordedDuringTheShiftIsActive() {
        when(creditEntryRepository.findByShiftId(1L)).thenReturn(List.of(activeCreditEntry(7L, "250.00")));

        assertThatThrownBy(() -> lifecycleSupportService.validateCloseCreditEntries(1L, BigDecimal.ZERO, List.of(), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("₹250.00 was already recorded during the shift");
    }

    @Test
    void zeroCreditTotalIsAcceptedWhenTheOnlyEntryIsBeingReplacedByNothing() {
        when(creditEntryRepository.findByShiftId(1L)).thenReturn(List.of(activeCreditEntry(7L, "250.00")));

        assertThat(lifecycleSupportService.validateCloseCreditEntries(1L, BigDecimal.ZERO, List.of(), List.of(7L)))
                .isEmpty();
    }

    @Test
    void shortageAboveThePumpThresholdNeedsTheOwnerAndOneAtTheThresholdDoesNot() {
        when(pumpLocationRepository.findById(1L)).thenReturn(Optional.of(
                PumpLocation.builder().id(1L).discrepancyEscalationThreshold(new BigDecimal("100.00")).build()));

        assertThat(lifecycleSupportService.escalateStatusIfNeeded(ShiftStatus.CLOSED_DISCREPANCY_PENDING, new BigDecimal("500.00"), 1L))
                .isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL);
        assertThat(lifecycleSupportService.escalateStatusIfNeeded(ShiftStatus.CLOSED_DISCREPANCY_PENDING, new BigDecimal("100.00"), 1L))
                .isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(lifecycleSupportService.escalateStatusIfNeeded(ShiftStatus.CLOSED_BALANCED, BigDecimal.ZERO, 1L))
                .isEqualTo(ShiftStatus.CLOSED_BALANCED);
    }

    @Test
    void creditTotalMatchingTheEntriesRecordedDuringTheShiftIsAcceptedWithoutNewEntries() {
        when(creditEntryRepository.findByShiftId(1L)).thenReturn(List.of(activeCreditEntry(7L, "250.00")));

        assertThat(lifecycleSupportService.validateCloseCreditEntries(1L, new BigDecimal("250.00"), List.of(), List.of()))
                .isEmpty();
    }

    @Test
    void correctedEntryReplacingTheOriginalMustAddUpToTheCreditTotalWithoutTheOriginal() {
        when(creditEntryRepository.findByShiftId(1L)).thenReturn(List.of(activeCreditEntry(7L, "250.00")));
        List<CloseShiftRequest.CreditEntryRequest> corrected = List.of(creditEntryRequest("300.00"));

        assertThatThrownBy(() -> lifecycleSupportService.validateCloseCreditEntries(
                1L, new BigDecimal("550.00"), corrected, List.of(7L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not match the credit total");
        assertThat(lifecycleSupportService.validateCloseCreditEntries(1L, new BigDecimal("300.00"), corrected, List.of(7L)))
                .containsExactlyElementsOf(corrected);
    }

    @Test
    void creditAmountsEnteredAtCloseAreRoundedToPaise() {
        CreditClient client = CreditClient.builder().id(5L).name("Fleet Co").build();
        when(creditAccountPolicyService.requireClientForCreditEntry(1L, 5L, "Fleet Co")).thenReturn(client);
        when(vehicleCreditCheck.check(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new VehicleCreditDecision(null, null, null, null, null));

        lifecycleSupportService.persistCloseCreditEntries(1L, 1L, LocalDate.of(2026, 11, 28),
                List.of(creditEntryRequest("100.555")), null, User.builder().id(2L).build());

        ArgumentCaptor<List<ShiftCreditEntry>> saved = ArgumentCaptor.captor();
        verify(creditEntryRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .satisfies(entry -> assertThat(entry.getAmount()).isEqualTo(new BigDecimal("100.56")));
    }

    private static CloseShiftRequest.CreditEntryRequest creditEntryRequest(String amount) {
        return new CloseShiftRequest.CreditEntryRequest("Fleet Co", 5L, null, new BigDecimal(amount), "PETROL",
                null, null, null, null, null);
    }

    private ShiftCreditEntry activeCreditEntry(Long id, String amount) {
        return ShiftCreditEntry.builder()
                .id(id)
                .shiftId(1L)
                .clientName("Fleet Co")
                .amount(new BigDecimal(amount))
                .build();
    }

    private ShiftFuelReading readingStartingAt(String start) {
        return ShiftFuelReading.builder()
                .shiftId(1L)
                .nozzleId(NOZZLE_ID)
                .fuelType(FuelType.PETROL)
                .startReading(new BigDecimal(start))
                .priceSnapshot(new BigDecimal("100.00"))
                .build();
    }

    private CloseShiftRequest closeRequest(String endReading, String cash) {
        CloseShiftRequest request = new CloseShiftRequest();
        request.setFuelReadings(List.of(new CloseShiftRequest.OutletEndReadingRequest(NOZZLE_ID, new BigDecimal(endReading))));
        request.setCashCollected(new BigDecimal(cash));
        request.setUpiCollected(BigDecimal.ZERO);
        request.setCardCollected(BigDecimal.ZERO);
        request.setFleetCardCollected(BigDecimal.ZERO);
        request.setCreditTotal(BigDecimal.ZERO);
        return request;
    }
}
