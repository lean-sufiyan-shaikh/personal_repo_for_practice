package com.ppms.cash;

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
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A safe count for yesterday the next morning, an owner's back-dated count, and what each is compared with. */
class LateSafeCountIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate TODAY = TestDates.today();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired private AuditLogRepository auditLogRepository;

    private PumpSetup setup;

    @BeforeEach
    void createPump() throws Exception {
        setup = createPumpWithStock("5000");
        cashEvent("CASH_IN", "1000.00", TODAY.minusDays(5));
    }

    @Test
    void managerCountsForYesterdayBeforeTheCutoffAgainstYesterdaysClosingBalance() throws Exception {
        assumeTrue(TestDates.now().toLocalTime().isBefore(LocalTime.of(23, 59)), "needs a minute before the cut-off");
        setCutoff("23:59").andExpect(jsonPath("$.safeCountCutoffTime").value("23:59:00"));
        cashEvent("CASH_IN", "300.00", TODAY);
        perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager())
                .andExpect(jsonPath("$.countCutoffTime").value("23:59:00"))
                .andExpect(jsonPath("$.yesterdayCountOpen").value(true));

        recordCount(setup.manager(), count("990.00", YESTERDAY, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countForDate").value(YESTERDAY.toString()))
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.backDateReason").doesNotExist())
                .andExpect(jsonPath("$.expectedAmount").value(1000.00))
                .andExpect(jsonPath("$.varianceAmount").value(-10.00));

        assertThat(auditsOf(AuditAction.SAFE_COUNTED_LATE)).singleElement()
                .satisfies(audit -> assertThat(audit.getDescription()).contains("late for " + YESTERDAY));
    }

    @Test
    void backDatedCountIsComparedWithTheBalanceAtTheEndOfThatDayIgnoringLaterMovements() throws Exception {
        LocalDate countedDay = TODAY.minusDays(3);
        cashEvent("CASH_OUT", "200.00", countedDay);
        cashEvent("CASH_IN", "500.00", TODAY.minusDays(2));
        cashEvent("CASH_IN", "100.00", TODAY);

        perform(get("/api/pumps/{pumpId}/safe/expected-balance", setup.pumpId()).param("date", countedDay.toString()),
                setup.owner())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(800.00));
        perform(get("/api/pumps/{pumpId}/safe/expected-balance", setup.pumpId()).param("date", TODAY.toString()),
                setup.owner())
                .andExpect(jsonPath("$.balance").value(1400.00));

        recordCount(setup.owner(), count("800.00", countedDay, "Count sheet found in the drawer"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedAmount").value(800.00))
                .andExpect(jsonPath("$.varianceType").doesNotExist());
    }

    @Test
    void managerCannotCountForYesterdayAfterTheCutoff() throws Exception {
        setCutoff("00:00");
        perform(get("/api/pumps/{pumpId}/safe", setup.pumpId()), setup.manager())
                .andExpect(jsonPath("$.yesterdayCountOpen").value(false));

        recordCount(setup.manager(), count("1000.00", YESTERDAY, "Forgot to count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAFE_COUNT_BACK_DATE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.params.date").value(YESTERDAY.toString()))
                .andExpect(jsonPath("$.params.cutoffTime").value("00:00"));
    }

    private ResultActions setCutoff(String time) throws Exception {
        return perform(patch("/api/pumps/{pumpId}/settings", setup.pumpId()), setup.owner(), Map.of("safeCountCutoffTime", time))
                .andExpect(status().isOk());
    }

    private void cashEvent(String type, String amount, LocalDate date) throws Exception {
        perform(post("/api/pumps/{pumpId}/cash-events", setup.pumpId()), setup.owner(),
                Map.of("eventType", type, "amount", new BigDecimal(amount), "description", "Movement",
                        "eventDate", date.toString()))
                .andExpect(status().isCreated());
    }

    private static Map<String, Object> count(String amount, LocalDate date, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("countedAmount", new BigDecimal(amount));
        body.put("countForDate", date.toString());
        body.put("backDateReason", reason);
        return body;
    }

    private ResultActions recordCount(User user, Map<String, Object> body) throws Exception {
        return perform(post("/api/pumps/{pumpId}/safe/counts", setup.pumpId()), user, body);
    }

    private List<AuditLog> auditsOf(AuditAction action) {
        return auditLogRepository.findByPumpIdOrderByCreatedAtDesc(setup.pumpId()).stream()
                .filter(audit -> audit.getAction() == action)
                .toList();
    }
}
