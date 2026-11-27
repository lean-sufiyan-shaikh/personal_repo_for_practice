package com.ppms.demo;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * An owner's demo pump: one per owner, filled with a fortnight of sample data, and removed with all of it in one
 * transaction. It has no DU limit of its own to spend, is left out of all-pump totals, and raises no notifications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoPumpService {

    static final String DEMO_PUMP_NAME = "Demo Pump";
    private static final int DEMO_MAX_DU_COUNT = 4;

    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final DemoPumpBuilder pumpBuilder;
    private final DemoActivityBuilder activityBuilder;
    private final DemoPumpDeleter pumpDeleter;
    private final AuditService auditService;

    @Transactional
    public PumpLocation createFor(Long ownerId, User actor) {
        User owner = requireOwner(ownerId);
        userRepository.findByIdForUpdate(owner.getId());
        if (pumpLocationRepository.findByOwnerIdAndDemoTrue(owner.getId()).isPresent()) {
            throw new BusinessException(ErrorCodes.DEMO_PUMP_ALREADY_EXISTS, Map.of(),
                    "A demo pump already exists. Delete it before creating a new one.");
        }
        PumpLocation pump = pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name(freeDemoName(owner.getId()))
                .address("Sample data for trying PPMS")
                .maxDuCount(DEMO_MAX_DU_COUNT)
                .demo(true)
                .build());

        DemoPumpLayout layout = pumpBuilder.build(pump, actor);
        activityBuilder.run(layout, actor);

        auditService.log(pump.getId(), AuditAction.DEMO_PUMP_CREATED, "PumpLocation", pump.getId().toString(),
                "Demo pump created with " + DemoActivityBuilder.DAYS + " days of sample data", actor);
        log.info("Demo pump created: pump={}, owner={}, by={}", pump.getId(), owner.getId(), actor.getId());
        return pump;
    }

    @Transactional
    public void deleteFor(Long ownerId, User actor) {
        User owner = requireOwner(ownerId);
        PumpLocation pump = pumpLocationRepository.findByOwnerIdAndDemoTrue(owner.getId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.DEMO_PUMP_NOT_FOUND, Map.of(),
                        "There is no demo pump to delete."));
        pumpLocationRepository.findByIdForUpdate(pump.getId());
        pumpLocationRepository.flush();
        int rows = pumpDeleter.delete(pump.getId());

        auditService.log(null, AuditAction.DEMO_PUMP_DELETED, "PumpLocation", pump.getId().toString(),
                "Demo pump deleted with its sample data (" + rows + " records) for owner " + owner.getId(), actor);
        log.info("Demo pump deleted: pump={}, owner={}, rows={}, by={}", pump.getId(), owner.getId(), rows, actor.getId());
    }

    private User requireOwner(Long ownerId) {
        return userRepository.findById(ownerId)
                .filter(user -> user.getRole() == UserRole.OWNER)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.USER, "Owner not found"));
    }

    private String freeDemoName(Long ownerId) {
        String name = DEMO_PUMP_NAME;
        for (int suffix = 2; pumpLocationRepository.existsByOwnerIdAndName(ownerId, name); suffix++) {
            name = DEMO_PUMP_NAME + " " + suffix;
        }
        return name;
    }
}
