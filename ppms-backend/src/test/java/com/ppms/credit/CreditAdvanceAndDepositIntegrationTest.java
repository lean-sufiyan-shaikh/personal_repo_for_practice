package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A payment above the balance, once confirmed, is an advance that new bills use up; an owner can pay it back.
 * A security deposit is held apart from the balance until an owner applies it to the dues or refunds it.
 */
class CreditAdvanceAndDepositIntegrationTest extends CreditDeskTestSupport {

    private long clientId;

    @BeforeEach
    void createClientOwingOneThousand() throws Exception {
        openPumpAndShift();
        clientId = createClient(null);
        sellOnCredit(clientId, "1000.00");
    }

    @Test
    void confirmedOverpaymentBecomesAnAdvanceThatTheNextSaleUsesUp() throws Exception {
        pay(clientId, "1500.00", false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_PAYMENT_EXCEEDS_PAYABLE"));

        pay(clientId, "1500.00", true).andExpect(status().isCreated());
        assertThat(client(clientId).get("advance").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("-500.00");

        sellOnCredit(clientId, "300.00");
        assertThat(client(clientId).get("advance").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(agingRow(clientId).get("daysOverdue").asLong()).isZero();
        String statement = perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/statement", setup.pumpId(),
                clientId), setup.owner()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(statement).contains("Advance (paid ahead): ₹200.00");
    }

    @Test
    void ownerRefundsAtMostTheAdvance() throws Exception {
        pay(clientId, "1500.00", true).andExpect(status().isCreated());
        Map<String, Object> tooMuch = Map.of("amount", new BigDecimal("500.01"), "paymentMode", "UPI",
                "reason", "Client asked");
        Map<String, Object> all = Map.of("amount", new BigDecimal("500.00"), "paymentMode", "UPI",
                "referenceNo", "UTR123", "reason", "Client asked");

        desk("advance-refunds", clientId, setup.manager(), all).andExpect(status().isForbidden());
        desk("advance-refunds", clientId, setup.owner(), tooMuch).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_ADVANCE_REFUND_EXCEEDS_ADVANCE"));
        JsonNode refund = json(desk("advance-refunds", clientId, setup.admin(), all).andExpect(status().isCreated()));

        assertThat(refund.get("kind").asText()).isEqualTo("ADVANCE_REFUND");
        assertThat(outstanding(clientId)).isEqualByComparingTo("0.00");
        assertThat(client(clientId).get("advance").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void depositIsHeldApartUntilAppliedToTheDuesAndCashMovesThroughTheSafe() throws Exception {
        desk("deposits", clientId, setup.manager(), Map.of("amount", new BigDecimal("5000.00"), "paymentMode", "CASH",
                "date", TODAY.toString())).andExpect(status().isCreated());
        assertThat(client(clientId).get("depositHeld").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(outstanding(clientId)).isEqualByComparingTo("1000.00");

        desk("deposits/apply", clientId, setup.manager(), Map.of("amount", new BigDecimal("600.00"), "reason", "Agreed"))
                .andExpect(status().isForbidden());
        desk("deposits/apply", clientId, setup.owner(), Map.of("amount", new BigDecimal("600.00"), "reason", "Agreed"))
                .andExpect(status().isCreated());
        assertThat(outstanding(clientId)).isEqualByComparingTo("400.00");
        assertThat(client(clientId).get("depositHeld").decimalValue()).isEqualByComparingTo("4400.00");
        desk("deposits/apply", clientId, setup.owner(), Map.of("amount", new BigDecimal("400.01"), "reason", "Agreed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_DEPOSIT_EXCEEDS_DUES"));

        desk("deposits/refund", clientId, setup.owner(), Map.of("amount", new BigDecimal("4400.01"),
                "paymentMode", "CASH", "reason", "Closing")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_DEPOSIT_EXCEEDS_HELD"));
        desk("deposits/refund", clientId, setup.owner(), Map.of("amount", new BigDecimal("4400.00"),
                "paymentMode", "CASH", "reason", "Closing")).andExpect(status().isCreated());

        assertThat(client(clientId).get("depositHeld").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForList("SELECT event_type || ':' || amount FROM cash_events WHERE pump_id = ?"
                + " AND description LIKE '%deposit%' ORDER BY id", String.class, setup.pumpId()))
                .containsExactly("CASH_IN:5000.00", "CASH_OUT:4400.00");
        JsonNode deposits = json(perform(get("/api/pumps/{pumpId}/credit-ledger/{clientId}/deposits", setup.pumpId(),
                clientId), setup.accountant()).andExpect(status().isOk()));
        assertThat(deposits).hasSize(3);
    }
}
