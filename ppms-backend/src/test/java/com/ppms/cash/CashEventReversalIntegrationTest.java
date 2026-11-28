package com.ppms.cash;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditLog;
import com.ppms.audit.AuditLogRepository;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Safe movements typed by hand are audited, never edited, and cancelled by a reversal entry with a reason. */
class CashEventReversalIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();

    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private CashEventRepository cashEventRepository;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("0");
    }

    @Test
    void openingBalanceIsOncePerPumpUntilReversed() throws Exception {
        long opening = id(record(setup.owner(), "OPENING_BALANCE", "5000.00", "Float from the owner")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordedByHand").value(true)));
        record(setup.admin(), "OPENING_BALANCE", "6000.00", "Second float")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CASH_OPENING_BALANCE_EXISTS"));

        reverse(setup.owner(), opening, "Typed 5000 instead of 500")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("CASH_OUT"))
                .andExpect(jsonPath("$.reversesEventId").value(opening))
                .andExpect(jsonPath("$.eventDate").value(TODAY.toString()));
        record(setup.owner(), "OPENING_BALANCE", "500.00", "Float from the owner").andExpect(status().isCreated());

        assertThat(balance()).isEqualByComparingTo("500.00");
        assertThat(cashEventRepository.findById(opening).orElseThrow().getReversedAt()).isNotNull();
    }

    @Test
    void managerRecordsCashMovementsButOnlyAnOwnerOrAdminReversesThem() throws Exception {
        record(setup.owner(), "OPENING_BALANCE", "1000.00", "Float").andExpect(status().isCreated());
        long cashOut = id(record(setup.manager(), "CASH_OUT", "300.00", "Tea and snacks").andExpect(status().isCreated()));

        reverse(setup.manager(), cashOut, "Wrong amount").andExpect(status().isForbidden());
        reverse(setup.admin(), cashOut, "Wrong amount")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("CASH_IN"))
                .andExpect(jsonPath("$.amount").value(300.00));

        assertThat(balance()).isEqualByComparingTo("1000.00");
        assertThat(auditsOf(AuditAction.CASH_EVENT_RECORDED)).hasSize(2);
        assertThat(auditsOf(AuditAction.CASH_EVENT_REVERSED)).singleElement()
                .satisfies(audit -> assertThat(audit.getDescription()).contains("Wrong amount"));
    }

    private ResultActions record(User user, String type, String amount, String description) throws Exception {
        return perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), user,
                Map.of("eventType", type, "amount", new BigDecimal(amount), "description", description,
                        "eventDate", TODAY.toString()));
    }

    private ResultActions reverse(User user, long eventId, String reason) throws Exception {
        return perform(post("/api/pumps/{pumpId}/cash-events/{eventId}/reverse", setup.pumpId(), eventId), user,
                Map.of("reason", reason));
    }

    private long id(ResultActions result) throws Exception {
        return json(result).get("id").asLong();
    }

    private BigDecimal balance() throws Exception {
        JsonNode events = json(perform(get("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner())
                .andExpect(status().isOk()));
        return events.get("currentBalance").decimalValue();
    }

    private List<AuditLog> auditsOf(AuditAction action) {
        return auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()).stream()
                .filter(audit -> audit.getAction() == action)
                .toList();
    }
}
