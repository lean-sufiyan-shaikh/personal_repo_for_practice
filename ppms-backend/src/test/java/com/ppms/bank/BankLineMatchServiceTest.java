package com.ppms.bank;

import com.ppms.common.exception.BusinessException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankLineMatchServiceTest {

    private static final long PUMP_ID = 4L;
    private static final long IMPORT_ID = 20L;
    private static final long LINE_ID = 21L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

    @Mock private BankStatementImportRepository importRepository;
    @Mock private BankStatementLineRepository lineRepository;
    @Mock private BankMatchCandidates candidates;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @InjectMocks private BankLineMatchService matchService;

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).build();

    @BeforeEach
    void givenPumpAndImport() {
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(PumpLocation.builder().id(PUMP_ID).build()));
        lenient().when(importRepository.findById(IMPORT_ID))
                .thenReturn(Optional.of(BankStatementImport.builder().id(IMPORT_ID).pumpId(PUMP_ID).build()));
    }

    @Test
    void ignoringALineNeedsAReason() {
        givenLine("0", "17.70");

        assertUpdateRefused(UpdateLineMatchRequest.builder().status("IGNORED").matchNotes(" ").build(),
                "BANK_LINE_IGNORE_REASON_REQUIRED");
    }

    @Test
    void matchedLineNeedsExactlyOneRecord() {
        givenLine("500.00", "0");

        assertUpdateRefused(UpdateLineMatchRequest.builder().status("MATCHED").build(), "BANK_LINE_MATCH_TARGET_REQUIRED");
        assertUpdateRefused(UpdateLineMatchRequest.builder().status("MATCHED").matchedPaymentId(5L).matchedSettlementId(6L).build(),
                "BANK_LINE_MATCH_TARGET_REQUIRED");
    }

    @Test
    void supplierPaymentCannotBeMatchedToMoneyComingIn() {
        givenLine("50000.00", "0");
        givenRecord(BankMatchRecordType.SUPPLIER_PAYMENT, 7L, "50000.00");

        assertUpdateRefused(UpdateLineMatchRequest.builder().status("MATCHED").matchedSupplierPaymentId(7L).build(),
                "BANK_LINE_DIRECTION_MISMATCH");
    }

    @Test
    void recordOfADifferentAmountIsMatchedOnlyWithANote() {
        givenLine("480.00", "0");
        givenRecord(BankMatchRecordType.CREDIT_PAYMENT, 5L, "500.00");

        assertUpdateRefused(UpdateLineMatchRequest.builder().status("MATCHED").matchedPaymentId(5L).build(),
                "BANK_LINE_AMOUNT_MISMATCH_NOTE_REQUIRED");
    }

    @Test
    void lineWithoutASuggestionHasNothingToAccept() {
        givenLine("500.00", "0");

        assertThatThrownBy(() -> matchService.acceptSuggestion(PUMP_ID, LINE_ID, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("BANK_LINE_NO_SUGGESTION"));
    }

    private void givenLine(String credit, String debit) {
        when(lineRepository.findByIdForUpdate(LINE_ID)).thenReturn(Optional.of(BankStatementLine.builder().id(LINE_ID)
                .importId(IMPORT_ID).txnDate(TODAY).creditAmount(new BigDecimal(credit)).debitAmount(new BigDecimal(debit))
                .matchStatus(BankLineMatchStatus.UNMATCHED).build()));
    }

    private void givenRecord(BankMatchRecordType type, long id, String amount) {
        when(candidates.require(PUMP_ID, new BankMatchRef(type, id)))
                .thenReturn(new MatchCandidate(type, id, TODAY, new BigDecimal(amount), null, null));
    }

    private void assertUpdateRefused(UpdateLineMatchRequest request, String code) {
        assertThatThrownBy(() -> matchService.updateLineMatch(PUMP_ID, LINE_ID, request, owner))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
        verify(lineRepository, never()).save(any());
    }
}
