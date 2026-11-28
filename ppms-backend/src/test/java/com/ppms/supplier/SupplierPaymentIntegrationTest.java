package com.ppms.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLogRepository;
import com.ppms.cash.CashEvent;
import com.ppms.cash.CashEventRepository;
import com.ppms.cash.CashEventType;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Supplier payments: dated on an open day, once per reference, out of the safe when paid in cash, voided with a reason. */
class SupplierPaymentIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private FuelSupplierPaymentRepository paymentRepository;
    @Autowired private CashEventRepository cashEventRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private PumpSetup setup;
    private long supplierId;

    @BeforeEach
    void createSupplier() throws Exception {
        setup = createPumpWithStock("0");
        supplierId = json(perform(post("/api/pumps/{pumpId}/suppliers", setup.pumpId()), setup.owner(),
                Map.of("name", "IOCL " + shortId(), "phone", "9876543210"))
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }

    @Test
    void cashPaymentLeavesTheSafeAndVoidingPutsItBack() throws Exception {
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", "OPENING_BALANCE", "amount", new BigDecimal("10000.00"), "description", "Float",
                        "eventDate", TODAY.toString())).andExpect(status().isCreated());
        pay(payment("CASH", "20000.00", "CH-1", TODAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_OUT_EXCEEDS_BALANCE"));

        JsonNode paid = json(pay(payment("CASH", "4000.00", "CH-1", TODAY)).andExpect(status().isCreated()));
        long paymentId = paid.get("id").asLong();
        CashEvent cashOut = cashEventRepository.findById(paid.get("cashEventId").asLong()).orElseThrow();
        assertThat(cashOut.getEventType()).isEqualTo(CashEventType.CASH_OUT);
        assertThat(cashOut.getAmount()).isEqualByComparingTo("4000.00");
        assertThat(balance()).isEqualByComparingTo("6000.00");

        voidPayment(paymentId, "Paid by cheque instead")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voidStatus").value("VOIDED"))
                .andExpect(jsonPath("$.voidReason").value("Paid by cheque instead"));

        assertThat(balance()).isEqualByComparingTo("10000.00");
        assertThat(cashEventRepository.findById(cashOut.getId()).orElseThrow().getReversedAt()).isNotNull();
        assertThat(auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()))
                .anySatisfy(audit -> {
                    assertThat(audit.getAction()).isEqualTo(AuditAction.SUPPLIER_PAYMENT_VOIDED);
                    assertThat(audit.getDescription()).contains("Paid by cheque instead");
                });
    }

    @Test
    void referenceIsUsedOncePerSupplierUntilThatPaymentIsVoided() throws Exception {
        long first = json(pay(payment("BANK_TRANSFER", "1000.00", "UTR-77", TODAY)).andExpect(status().isCreated()))
                .get("id").asLong();
        pay(payment("BANK_TRANSFER", "1000.00", " utr-77 ", TODAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PAYMENT_REFERENCE_DUPLICATE"));

        voidPayment(first, "Entered against the wrong supplier").andExpect(status().isOk());
        pay(payment("BANK_TRANSFER", "1000.00", "UTR-77", TODAY)).andExpect(status().isCreated());
    }

    @Test
    void repeatedRequestWithTheSameIdempotencyKeyRecordsOnePayment() throws Exception {
        Map<String, Object> body = payment("UPI", "2500.00", "UPI-1", TODAY);
        String key = "supplier-pay-" + shortId();

        JsonNode first = json(perform(post("/api/pumps/{pumpId}/supplier-payments", setup.pumpId())
                .header("Idempotency-Key", key), setup.owner(), body).andExpect(status().isCreated()));
        JsonNode replay = json(perform(post("/api/pumps/{pumpId}/supplier-payments", setup.pumpId())
                .header("Idempotency-Key", key), setup.owner(), body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(paymentRepository.findAll()).filteredOn(payment -> payment.getSupplierId().equals(supplierId)).hasSize(1);
    }

    @Test
    void closedDayRefusesPaymentsAndVoidsOnIt() throws Exception {
        long paymentId = json(pay(payment("UPI", "100.00", null, TODAY)).andExpect(status().isCreated())).get("id").asLong();

        perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), setup.manager(), Map.of("countedAmount", BigDecimal.ZERO))
                .andExpect(status().isCreated());
        perform(post("/api/pumps/{pumpId}/day-closes/{date}/close", setup.pumpId(), TODAY), setup.owner(), Map.of())
                .andExpect(status().isOk());
        pay(payment("UPI", "100.00", null, TODAY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"));
        voidPayment(paymentId, "Duplicate")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAY_CLOSED"));
        pay(payment("UPI", "100.00", null, TODAY.minusDays(1))).andExpect(status().isCreated());
    }

    @Test
    void onlyAnOwnerOrAdminVoidsAPayment() throws Exception {
        long paymentId = json(pay(payment("UPI", "100.00", null, TODAY)).andExpect(status().isCreated())).get("id").asLong();

        perform(post("/api/pumps/{pumpId}/supplier-payments/{id}/void", setup.pumpId(), paymentId), setup.manager(),
                Map.of("reason", "Mistake")).andExpect(status().isForbidden());
        perform(post("/api/pumps/{pumpId}/supplier-payments/{id}/void", setup.pumpId(), paymentId), setup.admin(),
                Map.of("reason", " ")).andExpect(status().isBadRequest());
        perform(post("/api/pumps/{pumpId}/supplier-payments/{id}/void", setup.pumpId(), paymentId), setup.admin(),
                Map.of("reason", "Mistake")).andExpect(status().isOk());
    }

    private Map<String, Object> payment(String mode, String amount, String reference, LocalDate date) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplierId", supplierId);
        body.put("amount", new BigDecimal(amount));
        body.put("paymentDate", date.toString());
        body.put("paymentMode", mode);
        body.put("referenceNo", reference);
        return body;
    }

    private ResultActions pay(Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/supplier-payments", setup.pumpId()), setup.owner(), body);
    }

    private ResultActions voidPayment(long paymentId, String reason) throws Exception {
        return perform(post("/api/pumps/{pumpId}/supplier-payments/{id}/void", setup.pumpId(), paymentId), setup.owner(),
                Map.of("reason", reason));
    }

    private BigDecimal balance() throws Exception {
        return json(perform(get("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner()).andExpect(status().isOk()))
                .get("currentBalance").decimalValue();
    }
}
