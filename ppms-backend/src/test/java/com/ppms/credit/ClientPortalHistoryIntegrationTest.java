package com.ppms.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.support.TestAddresses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The phone and PIN open a short portal session: a main account reads its group's last 90 days, a sub-account only
 * its own, and either can download this month's or last month's statement a few times.
 */
class ClientPortalHistoryIntegrationTest extends CreditDeskTestSupport {

    private static final String PIN = "482913";

    private String parentPhone;
    private String subPhone;
    private long subId;

    @BeforeEach
    void createGroupWithRecentAndOldSales() throws Exception {
        openPumpAndShift();
        parentPhone = uniquePhone();
        subPhone = uniquePhone();
        long parentId = createClientWithPhone(null, parentPhone);
        subId = createClientWithPhone(parentId, subPhone);
        sellOnCredit(parentId, "1000.00");
        sellOnCredit(subId, "300.00");
        backdate(sellOnCredit(parentId, "500.00"), 100);
        setPin(parentId);
        setPin(subId);
    }

    @Test
    void mainAccountReadsItsGroupsLastNinetyDaysAndASubAccountOnlyItsOwn() throws Exception {
        JsonNode groupHistory = json(history(sessionOf(parentPhone)).andExpect(status().isOk()));
        assertThat(groupHistory).hasSize(2);
        assertThat(groupHistory.findValuesAsText("type")).containsOnly("SALE");

        JsonNode subHistory = json(history(sessionOf(subPhone)).andExpect(status().isOk()));
        assertThat(subHistory).singleElement()
                .satisfies(row -> assertThat(row.get("amount").decimalValue()).isEqualByComparingTo("300.00"));
    }

    @Test
    void historyAndStatementsNeedALiveSession() throws Exception {
        history("not-a-session").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PORTAL_SESSION_EXPIRED"));
        statement(null, "CURRENT").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PORTAL_SESSION_EXPIRED"));
    }

    @Test
    void statementsOfThisAndLastMonthDownloadAFewTimesPerSession() throws Exception {
        String session = sessionOf(parentPhone);

        String current = statement(session, "CURRENT").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(current).contains("CREDIT ACCOUNT STATEMENT");
        statement(session, "PREVIOUS").andExpect(status().isOk());
        statement(session, "LAST_YEAR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PORTAL_STATEMENT_MONTH_NOT_ALLOWED"));
        statement(session, "CURRENT").andExpect(status().isOk());
        statement(session, "CURRENT").andExpect(status().isOk());
        statement(session, "CURRENT").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_STATEMENT_DOWNLOADS"));
    }

    private String sessionOf(String phone) throws Exception {
        JsonNode balance = json(mockMvc.perform(fromRandomAddress(post("/api/public/credit-balance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("pumpId", setup.pumpId(), "phone", phone, "pin", PIN))))
                .andExpect(status().isOk()));
        return balance.get("sessionToken").asText();
    }

    private ResultActions history(String session) throws Exception {
        return mockMvc.perform(fromRandomAddress(get("/api/public/credit-history"))
                .header(ClientPortalHistoryController.SESSION_HEADER, session));
    }

    private ResultActions statement(String session, String month) throws Exception {
        MockHttpServletRequestBuilder request = fromRandomAddress(get("/api/public/credit-statement")).param("month", month);
        return mockMvc.perform(session == null ? request : request.header(ClientPortalHistoryController.SESSION_HEADER, session));
    }

    private static MockHttpServletRequestBuilder fromRandomAddress(MockHttpServletRequestBuilder request) {
        String address = TestAddresses.randomPrivateAddress();
        return request.with(servletRequest -> {
            servletRequest.setRemoteAddr(address);
            return servletRequest;
        });
    }

    private void setPin(long clientId) throws Exception {
        perform(put("/api/pumps/{pumpId}/credit-clients/{clientId}/portal-pin", setup.pumpId(), clientId),
                setup.owner(), Map.of("pin", PIN)).andExpect(status().isNoContent());
    }

    private long createClientWithPhone(Long parentId, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Client " + shortId());
        body.put("phone", phone);
        body.put("parentClientId", parentId);
        return json(perform(post("/api/pumps/{pumpId}/credit-clients", setup.pumpId()), setup.manager(), body)
                .andExpect(status().is2xxSuccessful())).get("id").asLong();
    }
}
