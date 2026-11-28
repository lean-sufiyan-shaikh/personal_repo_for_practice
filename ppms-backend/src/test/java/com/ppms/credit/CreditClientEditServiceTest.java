package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.support.FixedBusinessClock;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditClientEditServiceTest {

    private static final long PUMP_ID = 7L;
    private static final String PHONE = "9876543210";

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditClientAccessService clientAccessService = mock(CreditClientAccessService.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final NotificationDedupService notificationDedupService = mock(NotificationDedupService.class);
    private final AuditService auditService = mock(AuditService.class);

    private final CreditClientEditService editService = new CreditClientEditService(clientRepository,
            clientAccessService, pumpLocationRepository, notificationDedupService, auditService,
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")));

    private final CreditClient client = CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").phone(PHONE)
            .portalPinHash("hashed-pin").build();

    @BeforeEach
    void givenClient() {
        when(clientAccessService.requireClientForPump(PUMP_ID, 1L)).thenReturn(client);
        when(clientRepository.save(any())).thenAnswer(call -> {
            CreditClient saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(2L);
            }
            return saved;
        });
    }

    @Test
    void managerCannotChangeThePhoneOrGstinButMayRenameAndAddNotes() {
        User manager = user(UserRole.MANAGER);

        assertThatThrownBy(() -> update(new UpdateCreditClientRequest(null, "9123456780", null, null, null), manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLIENT_PHONE_CHANGE_OWNER_ONLY");
        assertThatThrownBy(() -> update(new UpdateCreditClientRequest(null, null, null, "27AAPFU0939F1ZV", null), manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLIENT_GSTIN_CHANGE_OWNER_ONLY");

        CreditClient renamed = update(new UpdateCreditClientRequest("Manager Rename", null, "Pays on Fridays", null, null),
                manager);

        assertThat(renamed.getName()).isEqualTo("Manager Rename");
        assertThat(renamed.getNotes()).isEqualTo("Pays on Fridays");
    }

    @Test
    void managerMayDisableButOnlyAnOwnerOrAdminEnablesAClient() {
        editService.changeStatus(PUMP_ID, 1L, false, user(UserRole.MANAGER));

        assertThatThrownBy(() -> editService.changeStatus(PUMP_ID, 1L, true, user(UserRole.MANAGER)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLIENT_ENABLE_OWNER_ONLY");
        assertThat(editService.changeStatus(PUMP_ID, 1L, true, user(UserRole.ADMIN)).isActive()).isTrue();
    }

    @Test
    void newPhoneTurnsOffTheBalancePagePinTellsTheOwnerAndIsAuditedMasked() {
        User admin = user(UserRole.ADMIN);

        CreditClient saved = update(new UpdateCreditClientRequest(null, "9123456780", null, null, null), admin);

        assertThat(saved.getPortalPinHash()).isNull();
        verify(notificationDedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.CREDIT_CLIENT_PHONE_CHANGED),
                anyString(), anyString(), anyString(), anyMap());
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.CREDIT_CLIENT_UPDATED), eq("CreditClient"), eq("1"),
                description.capture(), eq(admin));
        assertThat(description.getValue()).contains("******6780", "balance-page PIN turned off")
                .doesNotContain("9123456780", PHONE);
    }

    @Test
    void savingTheSamePhoneKeepsThePinAndWritesNoAudit() {
        CreditClient saved = update(new UpdateCreditClientRequest(null, " " + PHONE + " ", null, null, null),
                user(UserRole.MANAGER));

        assertThat(saved.getPortalPinHash()).isEqualTo("hashed-pin");
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any(User.class));
        verify(clientRepository, never()).save(any());
    }

    @Test
    void newMainAccountStartsWithThePumpsDefaultLimitAndASubAccountWithNone() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(
                PumpLocation.builder().id(PUMP_ID).defaultCreditLimit(new BigDecimal("2500")).build()));
        when(clientAccessService.requireClientForPump(PUMP_ID, 1L)).thenReturn(
                CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").build());

        CreditClient main = editService.createClient(PUMP_ID, newClient("New Fleet", null), user(UserRole.MANAGER));
        CreditClient sub = editService.createClient(PUMP_ID, newClient("Driver", 1L), user(UserRole.MANAGER));

        assertThat(main.getCreditLimit()).isEqualByComparingTo("2500");
        assertThat(sub.getCreditLimit()).isEqualByComparingTo("0");
    }

    @Test
    void subAccountCannotHaveSubAccounts() {
        when(clientAccessService.requireClientForPump(PUMP_ID, 5L)).thenReturn(
                CreditClient.builder().id(5L).pumpId(PUMP_ID).name("Driver").parentClientId(1L).build());

        assertThatThrownBy(() -> editService.createClient(PUMP_ID, newClient("Helper", 5L), user(UserRole.OWNER)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_SUB_ACCOUNT_NESTING_NOT_ALLOWED");
    }

    @Test
    void auditKeepsOnlyTheLastFourDigitsOfAPhone() {
        assertThat(CreditClientEditService.maskedPhone(PHONE)).isEqualTo("******3210");
        assertThat(CreditClientEditService.maskedPhone("123")).isEqualTo("****");
        assertThat(CreditClientEditService.maskedPhone(null)).isEqualTo("none");
    }

    private CreditClient update(UpdateCreditClientRequest request, User user) {
        return editService.updateClient(PUMP_ID, 1L, request, user);
    }

    private static CreateCreditClientRequest newClient(String name, Long parentClientId) {
        CreateCreditClientRequest request = new CreateCreditClientRequest();
        request.setName(name);
        request.setParentClientId(parentClientId);
        return request;
    }

    private static User user(UserRole role) {
        User user = new User();
        user.setId(role.ordinal() + 100L);
        user.setRole(role);
        return user;
    }
}
