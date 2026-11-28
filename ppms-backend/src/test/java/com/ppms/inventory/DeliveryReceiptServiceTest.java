package com.ppms.inventory;

import com.ppms.common.exception.BusinessException;
import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.UndergroundTank;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryReceiptServiceTest {

    private static final Long PUMP_ID = 1L;
    private static final Long TANK_ID = 3L;
    private static final Long NOZZLE_ID = 11L;

    @Mock private NozzleRepository nozzleRepository;
    @InjectMocks private DeliveryReceiptService receiptService;

    private final UndergroundTank tank = UndergroundTank.builder().id(TANK_ID).pumpId(PUMP_ID).tankIdentifier("T-1").build();

    @BeforeEach
    void oneNozzleOnTheTank() {
        when(nozzleRepository.findActiveTankNozzlesByPumpId(PUMP_ID)).thenReturn(List.of(Nozzle.builder()
                .id(NOZZLE_ID).nozzleNumber(1).tankId(TANK_ID).fuelType(FuelType.PETROL).build()));
    }

    @Test
    void litresSoldWhileUnloadingCountAsReceived() {
        DeliveryReceiptService.MeasuredReceipt receipt = receiptService.measure(tank, check("2000", "13900", "1076"));

        assertThat(receipt.soldBetweenLitres()).isEqualByComparingTo("76");
        assertThat(receipt.receivedLitres()).isEqualByComparingTo("11976");
        assertThat(receipt.readings()).singleElement()
                .satisfies(reading -> assertThat(reading.getLitresSold()).isEqualByComparingTo("76"));
    }

    @Test
    void everyNozzleOnTheTankNeedsReadings() {
        ReceiptCheckRequest withoutReadings = new ReceiptCheckRequest(
                new BigDecimal("0"), new BigDecimal("1000"), null, null, null);

        assertThatThrownBy(() -> receiptService.measure(tank, withoutReadings))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("RECEIPT_NOZZLE_READINGS_MISSING");
                    assertThat(refusal.getParams()).containsEntry("nozzleNumbers", "#1");
                });
    }

    @Test
    void readingAfterUnloadingBelowTheReadingBeforeIsRefused() {
        assertThatThrownBy(() -> receiptService.measure(tank, check("0", "1000", "999")))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("RECEIPT_AFTER_READING_BELOW_BEFORE"));
    }

    @Test
    void deliveryLineWithoutAReceiptCheckFailsValidation() {
        RecordBatchDeliveryRequest.LineItem line = new RecordBatchDeliveryRequest.LineItem();
        line.setTankId(TANK_ID);
        line.setQuantityDelivered(new BigDecimal("1000"));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(line)).extracting(violation -> violation.getMessage())
                    .containsExactly("Receipt check is required");
        }
    }

    @Test
    void overDeliveryBeyondTheToleranceIsOver() {
        TankerDelivery delivery = applied("5000", check("0", "5100", "1000"), PumpLocation.builder().build());

        assertThat(delivery.getCheckStatus()).isEqualTo(DeliveryCheckStatus.OVER);
        assertThat(delivery.getVarianceLitres()).isEqualByComparingTo("100");
    }

    @Test
    void densitiesFurtherApartThanTheDefaultToleranceAreFlagged() {
        TankerDelivery flagged = applied("1000", check("0", "1000", "1000", "741.9", "745.0"), PumpLocation.builder().build());
        TankerDelivery close = applied("1000", check("0", "1000", "1000", "744.0", "745.0"), PumpLocation.builder().build());

        assertThat(flagged.isDensityFlagged()).isTrue();
        assertThat(close.isDensityFlagged()).isFalse();
    }

    @Test
    void widerPumpTolerancesAcceptAShortfallAndADensityGapTheDefaultsWouldFlag() {
        PumpLocation pump = PumpLocation.builder()
                .receiptTolerancePercent(new BigDecimal("1.00"))
                .densityTolerance(new BigDecimal("5.0"))
                .build();

        TankerDelivery delivery = applied("1000", check("0", "990", "1000", "741.0", "745.0"), pump);

        assertThat(delivery.getCheckStatus()).isEqualTo(DeliveryCheckStatus.OK);
        assertThat(delivery.isDensityFlagged()).isFalse();
        assertThat(delivery.getReceiptTolerancePercent()).isEqualByComparingTo("1.00");
    }

    private TankerDelivery applied(String invoiceLitres, ReceiptCheckRequest check, PumpLocation pump) {
        TankerDelivery delivery = TankerDelivery.builder().quantityDelivered(new BigDecimal(invoiceLitres)).build();
        receiptService.applyTo(delivery, receiptService.measure(tank, check), pump);
        return delivery;
    }

    private static ReceiptCheckRequest check(String dipBefore, String dipAfter, String readingAfter) {
        return check(dipBefore, dipAfter, readingAfter, null, null);
    }

    private static ReceiptCheckRequest check(String dipBefore, String dipAfter, String readingAfter,
                                             String observedDensity, String invoiceDensity) {
        return new ReceiptCheckRequest(new BigDecimal(dipBefore), new BigDecimal(dipAfter),
                List.of(new ReceiptCheckRequest.NozzleReading(NOZZLE_ID, new BigDecimal("1000"), new BigDecimal(readingAfter))),
                observedDensity != null ? new BigDecimal(observedDensity) : null,
                invoiceDensity != null ? new BigDecimal(invoiceDensity) : null);
    }
}
