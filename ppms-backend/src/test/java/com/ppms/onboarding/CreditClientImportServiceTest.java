package com.ppms.onboarding;

import com.ppms.audit.AuditService;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditOpeningBalanceRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditClientImportServiceTest {

    private static final long PUMP_ID = 7L;

    @Mock private CreditClientRepository clientRepository;
    @Mock private CreditOpeningBalanceRepository openingBalanceRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;

    @InjectMocks private CreditClientImportService service;

    @BeforeEach
    void setUp() {
        lenient().when(businessClock.today()).thenReturn(LocalDate.of(2026, 6, 15));
        givenPumpDefaultLimit("25000");
    }

    @Test
    void parentProblemsAndPhoneClashesAreReportedPerCell() {
        CreditClient group = CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Existing Group").phone("9876500001").build();
        CreditClient sub = CreditClient.builder().id(2L).pumpId(PUMP_ID).name("Existing Sub").phone("9876500002")
                .parentClientId(1L).build();
        when(clientRepository.findByPumpIdOrderByNameAsc(PUMP_ID)).thenReturn(List.of(group, sub));

        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "Orphan", "9876500010", "Nobody", null, null),
                row(3, "Too deep", "9876500011", "Existing Sub", null, null),
                row(4, "New Root", "9876500001", null, null, null),
                row(5, "Child", "9876500012", "Existing Group", "100", null),
                row(6, "Bad <name>", "9876500013", null, null, "123")));

        assertThat(report.rows()).extracting(ImportRowResult::outcome).containsOnly(RowOutcome.ERROR);
        assertThat(issues(report, 0)).containsExactly("parent_name:IMPORT_PARENT_NOT_FOUND");
        assertThat(issues(report, 1)).containsExactly("parent_name:CREDIT_SUB_ACCOUNT_NESTING_NOT_ALLOWED");
        assertThat(issues(report, 2)).containsExactly("phone:CREDIT_CLIENT_PHONE_TAKEN");
        assertThat(issues(report, 3)).containsExactly("credit_limit:IMPORT_CREDIT_LIMIT_ON_SUB_ACCOUNT");
        assertThat(issues(report, 4)).containsExactlyInAnyOrder("name:IMPORT_TEXT_HAS_ANGLE_BRACKETS", "gstin:GSTIN_INVALID");
    }

    @Test
    void duplicatesInTheFileAreErrorsAndAPhoneCannotSpanTwoGroups() {
        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "Alpha", "9876500020", null, null, null),
                row(3, "ALPHA", "9876500021", null, null, null),
                row(4, "Beta", "9876500020", null, null, null),
                row(5, "Alpha Sub", "9876500020", "Alpha", null, null)));

        assertThat(report.rows()).extracting(ImportRowResult::outcome).containsExactly(
                RowOutcome.CREATE, RowOutcome.ERROR, RowOutcome.ERROR, RowOutcome.CREATE);
        assertThat(issues(report, 1)).containsExactly("name:IMPORT_DUPLICATE_IN_FILE");
        assertThat(issues(report, 2)).containsExactly("phone:IMPORT_PHONE_IN_OTHER_GROUP");
    }

    @Test
    void existingClientIsSkipped() {
        when(clientRepository.findByPumpIdOrderByNameAsc(PUMP_ID)).thenReturn(List.of(
                CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Repeat Co").phone("9876500030").build()));

        ImportReport report = service.preview(PUMP_ID, request(row(2, "repeat co", "9876500030", null, null, null)));

        assertThat(report.skipCount()).isEqualTo(1);
        assertThat(issues(report, 0)).containsExactly("name:IMPORT_ALREADY_EXISTS");
    }

    @Test
    void limitCellTakesAnAmountOrUnlimitedAndBlankTakesThePumpDefault() {
        when(clientRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.importRows(PUMP_ID, request(
                row(2, "Trusted Fleet", "9876500040", null, "Unlimited", null),
                row(3, "Cash Only", "9876500041", null, "0", null),
                row(4, "Default Limit", "9876500042", null, null, null)), User.builder().id(1L).role(UserRole.OWNER).build());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CreditClient>> saved = ArgumentCaptor.forClass(List.class);
        verify(clientRepository, times(2)).saveAll(saved.capture());
        List<CreditClient> roots = saved.getAllValues().get(0);
        assertThat(roots).extracting(CreditClient::getName).containsExactly("Trusted Fleet", "Cash Only", "Default Limit");
        assertThat(roots.get(0).isUnlimitedCredit()).isTrue();
        assertThat(roots.get(1).isUnlimitedCredit()).isFalse();
        assertThat(roots.get(1).getCreditLimit()).isEqualByComparingTo("0");
        assertThat(roots.get(2).getCreditLimit()).isEqualByComparingTo("25000");
    }

    @Test
    void blankLimitIsRequiredWhenThePumpDefaultAllowsNoCreditAndWordsAreRefused() {
        givenPumpDefaultLimit("0");

        ImportReport report = service.preview(PUMP_ID, request(
                row(2, "No Limit Given", "9876500050", null, null, null),
                row(3, "Words", "9876500051", null, "lots", null)));

        assertThat(issues(report, 0)).containsExactly("credit_limit:IMPORT_CREDIT_LIMIT_REQUIRED");
        assertThat(issues(report, 1)).containsExactly("credit_limit:IMPORT_NUMBER_INVALID");
    }

    private void givenPumpDefaultLimit(String limit) {
        lenient().when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(
                PumpLocation.builder().id(PUMP_ID).defaultCreditLimit(new BigDecimal(limit)).build()));
    }

    private static CreditClientImportRequest request(CreditClientImportRequest.Row... rows) {
        return new CreditClientImportRequest(List.of(rows));
    }

    private static CreditClientImportRequest.Row row(int rowNumber, String name, String phone, String parentName,
                                                     String creditLimit, String gstin) {
        return new CreditClientImportRequest.Row(rowNumber, name, phone, parentName, creditLimit, null, null, gstin,
                null, null);
    }

    private static List<String> issues(ImportReport report, int rowIndex) {
        return report.rows().get(rowIndex).issues().stream()
                .map(issue -> issue.column() + ":" + issue.code())
                .toList();
    }
}
