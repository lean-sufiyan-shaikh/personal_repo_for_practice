package com.ppms.demo;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoPumpServiceTest {

    private static final long OWNER_ID = 3L;

    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private UserRepository userRepository;
    @Mock private DemoPumpBuilder pumpBuilder;
    @Mock private DemoActivityBuilder activityBuilder;
    @Mock private DemoPumpDeleter pumpDeleter;
    @Mock private AuditService auditService;

    @InjectMocks private DemoPumpService service;

    private final User owner = User.builder().id(OWNER_ID).role(UserRole.OWNER).build();

    @Test
    void anOwnerHasAtMostOneDemoPump() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(pumpLocationRepository.findByOwnerIdAndDemoTrue(OWNER_ID))
                .thenReturn(Optional.of(PumpLocation.builder().id(9L).demo(true).build()));

        assertThatThrownBy(() -> service.createFor(OWNER_ID, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.DEMO_PUMP_ALREADY_EXISTS);
        verify(pumpLocationRepository, never()).save(any());
    }

    @Test
    void deletingWithoutADemoPumpIsRefused() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(pumpLocationRepository.findByOwnerIdAndDemoTrue(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteFor(OWNER_ID, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.DEMO_PUMP_NOT_FOUND);
        verify(pumpDeleter, never()).delete(anyLong());
    }

    @Test
    void demoPumpIsOnlyMadeForAnOwner() {
        User manager = User.builder().id(5L).role(UserRole.MANAGER).build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.createFor(5L, owner)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void demoPumpTakesTheNextFreeNameWhenTheOwnerAlreadyUsesDemoPump() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(pumpLocationRepository.findByOwnerIdAndDemoTrue(OWNER_ID)).thenReturn(Optional.empty());
        when(pumpLocationRepository.existsByOwnerIdAndName(OWNER_ID, "Demo Pump")).thenReturn(true);
        when(pumpLocationRepository.save(any(PumpLocation.class))).thenAnswer(invocation -> {
            PumpLocation pump = invocation.getArgument(0);
            pump.setId(9L);
            return pump;
        });

        service.createFor(OWNER_ID, owner);

        ArgumentCaptor<PumpLocation> saved = ArgumentCaptor.forClass(PumpLocation.class);
        verify(pumpLocationRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Demo Pump 2");
        assertThat(saved.getValue().isDemo()).isTrue();
    }
}
