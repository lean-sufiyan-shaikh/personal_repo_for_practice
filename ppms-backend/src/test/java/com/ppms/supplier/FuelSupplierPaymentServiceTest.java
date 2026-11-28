package com.ppms.supplier;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.PaymentMode;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FuelSupplierPaymentServiceTest {

    private static final Long PUMP_ID = 2L;
    private static final Long SUPPLIER_ID = 11L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    private final FuelSupplierPaymentRepository paymentRepository = mock(FuelSupplierPaymentRepository.class);
    private final FuelSupplierRepository supplierRepository = mock(FuelSupplierRepository.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final CashDrawerService cashDrawerService = mock(CashDrawerService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final FuelSupplierPaymentService service = new FuelSupplierPaymentService(paymentRepository, supplierRepository,
            mock(TankerDeliveryRepository.class), userRepository, pumpLocationRepository, cashDrawerService,
            mock(ClosedDayGuard.class), auditService, businessClock);

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).fullName("Owner").build();
    private final FuelSupplier supplier = FuelSupplier.builder().id(SUPPLIER_ID).pumpId(PUMP_ID).name("IOCL").active(true).build();

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(TODAY);
        when(businessClock.now()).thenReturn(OffsetDateTime.parse("2026-11-27T10:00:00+05:30"));
        when(supplierRepository.findByIdAndPumpId(SUPPLIER_ID, PUMP_ID)).thenReturn(Optional.of(supplier));
        when(supplierRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(new PumpLocation()));
        when(paymentRepository.save(any(FuelSupplierPayment.class))).thenAnswer(invocation -> {
            FuelSupplierPayment payment = invocation.getArgument(0);
            if (payment.getId() == null) {
                payment.setId(70L);
            }
            return payment;
        });
    }

    @Test
    void cashPaymentTakesTheMoneyOutOfTheSafe() {
        when(cashDrawerService.recordAutomaticCashOut(eq(PUMP_ID), any(), eq(TODAY), anyString(), eq(owner)))
                .thenReturn(CashEvent.builder().id(5L).build());

        SupplierPaymentResponse paid = service.record(PUMP_ID, request(PaymentMode.CASH, "4000", "CH-1", TODAY), owner);

        assertThat(paid.getCashEventId()).isEqualTo(5L);
        assertThat(paid.getAmount()).isEqualByComparingTo("4000.00");
        verify(cashDrawerService).recordAutomaticCashOut(eq(PUMP_ID), eq(new BigDecimal("4000.00")), eq(TODAY), anyString(), eq(owner));
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.SUPPLIER_PAYMENT_RECORDED), anyString(), anyString(), anyString(), eq(owner));
    }

    @Test
    void bankPaymentLeavesTheSafeAloneAndKeepsTheTrimmedReference() {
        SupplierPaymentResponse paid = service.record(PUMP_ID, request(PaymentMode.BANK_TRANSFER, "1000", " UTR-77 ", TODAY), owner);

        assertThat(paid.getReferenceNo()).isEqualTo("UTR-77");
        assertThat(paid.getCashEventId()).isNull();
        verify(paymentRepository).existsActiveWithReference(SUPPLIER_ID, "UTR-77");
        verify(cashDrawerService, never()).recordAutomaticCashOut(any(), any(), any(), anyString(), any());
    }

    @Test
    void referenceAlreadyUsedWithTheSupplierIsRefused() {
        when(paymentRepository.existsActiveWithReference(SUPPLIER_ID, "UTR-77")).thenReturn(true);

        assertThatThrownBy(() -> service.record(PUMP_ID, request(PaymentMode.BANK_TRANSFER, "1000", "UTR-77", TODAY), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_PAYMENT_REFERENCE_DUPLICATE));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void paymentDatedInTheFutureIsRefused() {
        assertThatThrownBy(() -> service.record(PUMP_ID, request(PaymentMode.UPI, "100", null, TODAY.plusDays(1)), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_PAYMENT_DATE_IN_FUTURE));
    }

    @Test
    void paymentToAnInactiveSupplierIsRefused() {
        supplier.setActive(false);

        assertThatThrownBy(() -> service.record(PUMP_ID, request(PaymentMode.UPI, "100", null, TODAY), owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_INACTIVE));
    }

    @Test
    void voidingACashPaymentPutsTheMoneyBackAndCannotBeDoneTwice() {
        FuelSupplierPayment payment = FuelSupplierPayment.builder().id(70L).pumpId(PUMP_ID).supplierId(SUPPLIER_ID)
                .amount(new BigDecimal("4000.00")).paymentDate(TODAY).paymentMode(PaymentMode.CASH).cashEventId(5L)
                .recordedByUserId(1L).build();
        when(paymentRepository.findByIdAndPumpId(70L, PUMP_ID)).thenReturn(Optional.of(payment));

        SupplierPaymentResponse voided = service.voidPayment(PUMP_ID, 70L, " Paid by cheque instead ", owner);

        assertThat(voided.getVoidStatus()).isEqualTo(FuelSupplierPayment.VOIDED);
        assertThat(voided.getVoidReason()).isEqualTo("Paid by cheque instead");
        verify(cashDrawerService).reverseAutomaticEvent(eq(PUMP_ID), eq(5L), anyString(), eq(owner));
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.SUPPLIER_PAYMENT_VOIDED), anyString(), anyString(),
                description.capture(), eq(owner));
        assertThat(description.getValue()).contains("Paid by cheque instead");

        assertThatThrownBy(() -> service.voidPayment(PUMP_ID, 70L, "Again", owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_PAYMENT_ALREADY_VOIDED));
    }

    private static RecordSupplierPaymentRequest request(PaymentMode mode, String amount, String reference, LocalDate date) {
        return new RecordSupplierPaymentRequest(SUPPLIER_ID, null, new BigDecimal(amount), date, mode, reference, null);
    }
}
