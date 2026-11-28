package com.ppms.invoice;

import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.user.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditInvoiceRunServiceTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditInvoiceService invoiceService = mock(CreditInvoiceService.class);
    private final CreditInvoiceRunService runService = new CreditInvoiceRunService(clientRepository, invoiceService);

    @Test
    void runCountsIssuedSkippedAndFailedClients() {
        givenPump(false);
        when(clientRepository.findByPumpIdOrderByNameAsc(PUMP_ID)).thenReturn(List.of(client(1L, null),
                client(2L, null), client(3L, null), client(4L, null)));
        givenIssue(1L, false, Optional.of(new CreditInvoice()));
        givenIssue(2L, false, Optional.of(new CreditInvoice()));
        givenIssue(3L, false, Optional.empty());
        when(invoiceService.issueInNewTransaction(eq(PUMP_ID), eq(4L), any(), any(), eq(false), any()))
                .thenThrow(new IllegalStateException("broken"));

        CreditInvoiceRunService.InvoiceRunResult result = runService.issueForAllClients(PUMP_ID, DAY, DAY, new User());

        assertThat(result.invoicesIssued()).isEqualTo(2);
        assertThat(result.clientsSkipped()).isEqualTo(1);
        assertThat(result.failedClientIds()).containsExactly(4L);
    }

    @Test
    void pumpDefaultOfGroupInvoicesBillsSubAccountsOnlyThroughTheirParent() {
        givenPump(true);
        when(clientRepository.findByPumpIdOrderByNameAsc(PUMP_ID)).thenReturn(List.of(client(1L, null), client(2L, 1L)));
        givenIssue(1L, true, Optional.of(new CreditInvoice()));

        CreditInvoiceRunService.InvoiceRunResult result = runService.issueForAllClients(PUMP_ID, DAY, DAY, new User());

        assertThat(result.invoicesIssued()).isEqualTo(1);
        assertThat(result.clientsSkipped()).isZero();
        verify(invoiceService, never()).issueInNewTransaction(eq(PUMP_ID), eq(2L), any(), any(), anyBoolean(), any());
    }

    private void givenIssue(long clientId, boolean coverSubAccounts, Optional<CreditInvoice> result) {
        when(invoiceService.issueInNewTransaction(eq(PUMP_ID), eq(clientId), any(), any(), eq(coverSubAccounts), any()))
                .thenReturn(result);
    }

    private void givenPump(boolean groupInvoiceByDefault) {
        when(invoiceService.requirePumpWithGstDetails(PUMP_ID))
                .thenReturn(PumpLocation.builder().id(PUMP_ID).groupInvoiceByDefault(groupInvoiceByDefault).build());
    }

    private static CreditClient client(long id, Long parentId) {
        return CreditClient.builder().id(id).pumpId(PUMP_ID).name("Client " + id).parentClientId(parentId).build();
    }
}
