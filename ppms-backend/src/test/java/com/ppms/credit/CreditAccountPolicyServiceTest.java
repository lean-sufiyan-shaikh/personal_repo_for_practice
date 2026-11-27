package com.ppms.credit;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.support.FixedBusinessClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditAccountPolicyServiceTest {

    private static final long PUMP_ID = 7L;

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);

    private final CreditAccountPolicyService policyService = new CreditAccountPolicyService(clientRepository,
            mock(CreditInterestService.class), mock(CreditExtensionRepository.class), mock(CreditOverdueService.class),
            mock(PumpLocationRepository.class),
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")),
            mock(EntityManager.class), mock(CreditCloseExceptionRepository.class), mock(AuditService.class));

    @Test
    void clientIsFoundByIdOnlyWhenItBelongsToThePump() {
        CreditClient client = client(1L, PUMP_ID, null, true);
        CreditClient otherPumpClient = client(2L, PUMP_ID + 1, null, true);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(clientRepository.findById(2L)).thenReturn(Optional.of(otherPumpClient));

        assertThat(policyService.requireClientForCreditEntry(PUMP_ID, 1L, "ignored")).isSameAs(client);
        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, 2L, "ignored"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void clientIsFoundByExactNameAndAnUnknownNameIsRefused() {
        CreditClient client = client(1L, PUMP_ID, null, true);
        when(clientRepository.findByPumpIdAndNameAndParentClientIdIsNull(PUMP_ID, "Fleet Co")).thenReturn(List.of(client));
        when(clientRepository.findByPumpIdAndNameAndParentClientIdIsNull(PUMP_ID, "Walk-in")).thenReturn(List.of());

        assertThat(policyService.requireClientForCreditEntry(PUMP_ID, null, "  Fleet Co ")).isSameAs(client);
        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, null, "Walk-in"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No credit client named 'Walk-in'");
        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, null, " "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nameMatchingTwoMainAccountsIsRefusedInsteadOfPickingOne() {
        when(clientRepository.findByPumpIdAndNameAndParentClientIdIsNull(PUMP_ID, "Fleet Co"))
                .thenReturn(List.of(client(1L, PUMP_ID, null, true), client(2L, PUMP_ID, null, true)));

        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, null, "Fleet Co"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLIENT_NAME_AMBIGUOUS");
    }

    @Test
    void disabledClientOrSubAccountOfADisabledParentCannotTakeCredit() {
        CreditClient disabledParent = client(1L, PUMP_ID, null, false);
        CreditClient subAccount = client(2L, PUMP_ID, 1L, true);
        CreditClient activeStandalone = client(3L, PUMP_ID, null, true);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(disabledParent));

        assertThatThrownBy(() -> policyService.requireClientAcceptsCredit(disabledParent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Credit is disabled");
        assertThatThrownBy(() -> policyService.requireClientAcceptsCredit(subAccount))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("parent account");
        assertThatNoException().isThrownBy(() -> policyService.requireClientAcceptsCredit(activeStandalone));
    }

    @Test
    void creditIsHeldFromTheHoldDaysOnUnlessAHoldOverrideIsInEffect() {
        CreditExtension waiver = extension(CreditExtensionType.OVERDUE_BLOCK_WAIVER);
        CreditExtension override = extension(CreditExtensionType.CREDIT_HOLD_OVERRIDE);

        assertThat(CreditAccountPolicyService.isOnHold(45, 44, List.of())).isFalse();
        assertThat(CreditAccountPolicyService.isOnHold(45, 45, List.of())).isTrue();
        assertThat(CreditAccountPolicyService.isOnHold(45, 90, List.of(waiver))).isTrue();
        assertThat(CreditAccountPolicyService.isOnHold(45, 90, List.of(waiver, override))).isFalse();
        assertThat(CreditAccountPolicyService.isOnHold(null, 400, List.of())).isFalse();
    }

    private static CreditExtension extension(CreditExtensionType type) {
        return CreditExtension.builder().extensionType(type).build();
    }

    private static CreditClient client(Long id, Long pumpId, Long parentClientId, boolean active) {
        return CreditClient.builder()
                .id(id)
                .pumpId(pumpId)
                .name("Client " + id)
                .parentClientId(parentClientId)
                .isActive(active)
                .build();
    }
}
