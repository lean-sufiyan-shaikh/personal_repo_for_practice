package com.ppms.credit;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditLimitServiceTest {

    private static final long PUMP_ID = 7L;

    private final CreditClientAccessService clientAccessService = mock(CreditClientAccessService.class);
    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditAccountPolicyService accountPolicyService = mock(CreditAccountPolicyService.class);
    private final CreditLimitChangeRepository limitChangeRepository = mock(CreditLimitChangeRepository.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);

    private final CreditLimitService limitService = new CreditLimitService(clientAccessService, clientRepository,
            accountPolicyService, limitChangeRepository, pumpLocationRepository, mock(UserRepository.class),
            mock(AuditService.class));

    private final CreditClient client = CreditClient.builder().id(1L).pumpId(PUMP_ID).name("Fleet Co").build();

    @BeforeEach
    void givenClientAndPumpWithManagerCap() {
        when(clientAccessService.requireClientForPump(PUMP_ID, 1L)).thenReturn(client);
        when(accountPolicyService.lockLimitHolder(client)).thenReturn(client);
        when(clientRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(
                PumpLocation.builder().id(PUMP_ID).managerCreditLimitCap(new BigDecimal("50000")).build()));
    }

    @Test
    void managerRaisesALimitUpToTheCapButNotBeyondIt() {
        change("50000", false, UserRole.MANAGER);

        assertThatThrownBy(() -> change("50000.01", false, UserRole.MANAGER))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_LIMIT_ABOVE_MANAGER_CAP");
                    assertThat(refusal.getParams()).containsEntry("cap", "50000.00");
                });
        assertThat(client.getCreditLimit()).isEqualByComparingTo("50000");
    }

    @Test
    void managerCannotGiveUnlimitedCredit() {
        assertThatThrownBy(() -> change("0", true, UserRole.MANAGER))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_LIMIT_UNLIMITED_OWNER_ONLY");
    }

    @Test
    void managerMayLowerALimitAboveTheCapAndReplaceUnlimitedCreditWithALimit() {
        change("80000", false, UserRole.ADMIN);
        change("60000", false, UserRole.MANAGER);
        change("0", true, UserRole.OWNER);
        change("70000", false, UserRole.MANAGER);

        assertThat(client.getCreditLimit()).isEqualByComparingTo("70000");
        assertThat(client.isUnlimitedCredit()).isFalse();
    }

    @Test
    void changeIsKeptWithTheOldAndNewLimitWhoAndWhy() {
        client.setCreditLimit(new BigDecimal("1000"));

        change("0", true, UserRole.OWNER);

        ArgumentCaptor<CreditLimitChange> saved = ArgumentCaptor.forClass(CreditLimitChange.class);
        verify(limitChangeRepository).save(saved.capture());
        assertThat(saved.getValue().getOldLimit()).isEqualByComparingTo("1000");
        assertThat(saved.getValue().isNewUnlimited()).isTrue();
        assertThat(saved.getValue().getReason()).isEqualTo("Limit review");
        assertThat(saved.getValue().getChangedByUserId()).isEqualTo(UserRole.OWNER.ordinal() + 100L);
    }

    @Test
    void settingTheSameLimitAgainKeepsNoChange() {
        client.setUnlimitedCredit(true);

        change("0", true, UserRole.OWNER);

        verify(limitChangeRepository, never()).save(any());
    }

    @Test
    void limitOfASubAccountIsRefusedBecauseItIsSetOnTheMainAccount() {
        CreditClient child = CreditClient.builder().id(2L).pumpId(PUMP_ID).name("Driver").parentClientId(1L).build();
        when(clientAccessService.requireClientForPump(PUMP_ID, 2L)).thenReturn(child);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> limitService.changeLimit(PUMP_ID, 2L, request("1000", false), user(UserRole.OWNER)))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_LIMIT_ON_SUB_ACCOUNT");
                    assertThat(refusal.getParams()).containsEntry("parentName", "Fleet Co");
                });
    }

    private void change(String limit, boolean unlimited, UserRole role) {
        limitService.changeLimit(PUMP_ID, 1L, request(limit, unlimited), user(role));
    }

    private static UpdateCreditLimitRequest request(String limit, boolean unlimited) {
        UpdateCreditLimitRequest request = new UpdateCreditLimitRequest();
        request.setCreditLimit(new BigDecimal(limit));
        request.setUnlimited(unlimited);
        request.setReason(" Limit review ");
        return request;
    }

    private static User user(UserRole role) {
        User user = new User();
        user.setId(role.ordinal() + 100L);
        user.setRole(role);
        return user;
    }
}
