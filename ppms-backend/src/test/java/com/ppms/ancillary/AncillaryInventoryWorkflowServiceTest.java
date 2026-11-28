package com.ppms.ancillary;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AncillaryInventoryWorkflowServiceTest {

    private static final Long PUMP_ID = 1L;
    private static final Long PRODUCT_ID = 7L;
    private static final Long LOT_ID = 70L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    @Mock private AncillaryProductRepository productRepository;
    @Mock private AncillaryProductPriceRepository priceRepository;
    @Mock private AncillaryStockLotRepository lotRepository;
    @Mock private AncillaryStockDeliveryRepository deliveryRepository;
    @Mock private AncillaryProductAccessService accessService;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @InjectMocks private AncillaryInventoryWorkflowService workflowService;

    private final User manager = User.builder().id(11L).role(UserRole.MANAGER).assignedPumpId(PUMP_ID).build();
    private final User owner = User.builder().id(10L).role(UserRole.OWNER).assignedPumpId(PUMP_ID).build();
    private AncillaryProduct product;
    private AncillaryStockLot lot;

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @BeforeEach
    void productWithTenUnitsInOneLot() {
        product = AncillaryProduct.builder()
                .id(PRODUCT_ID).pumpId(PUMP_ID).name("Engine Oil").currentStockUnits(10).lowStockThreshold(2)
                .status(AncillaryProductStatus.ACTIVE).build();
        lot = AncillaryStockLot.builder()
                .id(LOT_ID).productId(PRODUCT_ID).pumpId(PUMP_ID).originalQuantity(10).remainingQuantity(10)
                .costPricePerUnit(new BigDecimal("300.00")).build();
        when(accessService.requireProductForPumpForUpdate(PRODUCT_ID, PUMP_ID)).thenReturn(product);
        when(accessService.requireProductIdOfLot(LOT_ID, PUMP_ID)).thenReturn(PRODUCT_ID);
        when(accessService.requireLotForPump(LOT_ID, PUMP_ID)).thenReturn(lot);
        when(lotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.zone()).thenReturn(ZONE);
        when(businessClock.now()).thenReturn(TODAY.atTime(10, 0).atZone(ZONE).toOffsetDateTime());
        when(priceRepository.findFirstByProductIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(eq(PRODUCT_ID), any()))
                .thenReturn(Optional.of(AncillaryProductPrice.builder().pricePerUnit(new BigDecimal("450.00")).build()));
    }

    @Test
    void saleOfMoreUnitsThanInStockIsRefused() {
        assertRefusedWith(() -> workflowService.recordSale(PUMP_ID, sale(11, "450.00"), manager), "PRODUCT_INSUFFICIENT_STOCK");
    }

    @Test
    void managerCannotSellAtADifferentPriceThanConfigured() {
        assertRefusedWith(() -> workflowService.recordSale(PUMP_ID, sale(1, "400.00"), manager),
                "PRODUCT_PRICE_OVERRIDE_NOT_ALLOWED");
    }

    @Test
    void productWithoutAPriceCannotBeSold() {
        when(priceRepository.findFirstByProductIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(eq(PRODUCT_ID), any()))
                .thenReturn(Optional.empty());

        assertRefusedWith(() -> workflowService.recordSale(PUMP_ID, sale(1, "80.00"), manager), "PRODUCT_SELLING_PRICE_NOT_SET");
    }

    @Test
    void backfillingASaleForTodayIsRefused() {
        assertRefusedWith(() -> workflowService.backfillSale(PUMP_ID, backfill(TODAY, TransactionPaymentMode.CASH), owner),
                "BACKFILL_SALE_DATE_NOT_IN_PAST");
    }

    @Test
    void backfilledSaleBeforeAnyRecordedPriceIsRefused() {
        when(priceRepository.findFirstByProductIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(eq(PRODUCT_ID), any()))
                .thenReturn(Optional.empty());

        assertRefusedWith(() -> workflowService.backfillSale(PUMP_ID, backfill(TODAY.minusDays(1), TransactionPaymentMode.CASH), owner),
                "PRODUCT_HISTORICAL_PRICE_NOT_SET");
    }

    @Test
    void backfilledCreditSaleWithoutAClientIsRefused() {
        BackfillSaleRequest request = backfill(TODAY.minusDays(1), TransactionPaymentMode.CREDIT);
        request.setClientName("Walk-in");

        assertRefusedWith(() -> workflowService.backfillSale(PUMP_ID, request, owner), "PRODUCT_CREDIT_SALE_NEEDS_CLIENT");
    }

    @Test
    void correctingALotMovesTheProductStockByTheSameNumberOfUnits() {
        UpdateLotRequest request = new UpdateLotRequest();
        request.setRemainingQuantity(7);

        workflowService.updateLot(PUMP_ID, LOT_ID, request, owner);

        assertThat(product.getCurrentStockUnits()).isEqualTo(7);
        assertThat(lot.getStatus()).isEqualTo(AncillaryLotStatus.ACTIVE);
    }

    @Test
    void lotCannotHoldMoreThanWasDelivered() {
        UpdateLotRequest request = new UpdateLotRequest();
        request.setRemainingQuantity(11);

        assertRefusedWith(() -> workflowService.updateLot(PUMP_ID, LOT_ID, request, owner), "LOT_REMAINING_ABOVE_ORIGINAL");
        assertThat(product.getCurrentStockUnits()).isEqualTo(10);
    }

    @Test
    void emptyingALotMarksItExhaustedAndEmptiesTheProduct() {
        UpdateLotRequest request = new UpdateLotRequest();
        request.setRemainingQuantity(0);

        workflowService.updateLot(PUMP_ID, LOT_ID, request, owner);

        assertThat(lot.getStatus()).isEqualTo(AncillaryLotStatus.EXHAUSTED);
        assertThat(product.getCurrentStockUnits()).isZero();
    }

    @Test
    void patchingOnlyTheBrandKeepsTheLowStockThreshold() {
        UpdateProductRequest request = new UpdateProductRequest();
        request.setBrand(" Shell ");

        workflowService.updateProduct(PUMP_ID, PRODUCT_ID, request);

        assertThat(product.getBrand()).isEqualTo("Shell");
        assertThat(product.getLowStockThreshold()).isEqualTo(2);
    }

    @Test
    void deactivatingAProductMarksItInactive() {
        workflowService.setProductStatus(PUMP_ID, PRODUCT_ID, AncillaryProductStatus.INACTIVE);

        assertThat(product.getStatus()).isEqualTo(AncillaryProductStatus.INACTIVE);
        verify(productRepository).save(product);
    }

    @Test
    void gstRateAboveOneHundredPercentFailsValidation() {
        UpdateProductRequest request = new UpdateProductRequest();
        request.setGstRatePercent(new BigDecimal("150"));

        assertThat(VALIDATORS.getValidator().validate(request)).extracting(ConstraintViolation::getMessage)
                .containsExactly("GST rate cannot exceed 100%");
    }

    @Test
    void productWithASellingPriceBelowOnePaisaFailsValidation() {
        CreateProductRequest request = new CreateProductRequest();
        request.setName("Coolant");
        request.setPackageSize(BigDecimal.ONE);
        request.setUnitOfMeasure(UnitOfMeasure.LITRE);
        request.setSellingPricePerUnit(BigDecimal.ZERO);

        assertThat(VALIDATORS.getValidator().validate(request)).extracting(ConstraintViolation::getMessage)
                .containsExactly("Price must be at least 0.01");
    }

    private void assertRefusedWith(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                refusal -> assertThat(refusal.getCode()).isEqualTo(code));
        verify(productRepository, never()).save(any());
    }

    private static RecordAncillarySaleRequest sale(int units, String price) {
        RecordAncillarySaleRequest request = new RecordAncillarySaleRequest();
        request.setProductId(PRODUCT_ID);
        request.setQuantityUnits(units);
        request.setSellingPricePerUnit(new BigDecimal(price));
        request.setPaymentMode(TransactionPaymentMode.CASH);
        return request;
    }

    private static BackfillSaleRequest backfill(LocalDate saleDate, TransactionPaymentMode paymentMode) {
        BackfillSaleRequest request = new BackfillSaleRequest();
        request.setProductId(PRODUCT_ID);
        request.setSaleDate(saleDate);
        request.setQuantityUnits(2);
        request.setPaymentMode(paymentMode);
        return request;
    }
}
