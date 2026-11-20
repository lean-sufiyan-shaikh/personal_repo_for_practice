package com.ppms.pump;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditClientRepository;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PumpService {

    private static final String HAS_DATA_MESSAGE =
            "This pump has recorded data and cannot be deleted. Ask the Super Admin to suspend it instead.";

    private final PumpLocationRepository pumpLocationRepository;
    private final DispensaryUnitRepository duRepository;
    private final NozzleRepository nozzleRepository;
    private final UndergroundTankRepository tankRepository;
    private final ShiftRepository shiftRepository;
    private final UserRepository userRepository;
    private final CreditClientRepository creditClientRepository;
    private final TankerDeliveryRepository tankerDeliveryRepository;

    /** Only a pump that was set up but never used can be deleted; its equipment is removed with it. */
    @Transactional
    public void deletePump(Long pumpId, User currentUser) {
        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .filter(found -> found.getOwnerId().equals(currentUser.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found"));

        if (shiftRepository.existsByPumpId(pumpId)) {
            throw new BusinessException("This pump has shifts on record and cannot be deleted.");
        }
        if (userRepository.existsByAssignedPumpId(pumpId)) {
            throw new BusinessException("This pump still has staff assigned. Move or remove them first.");
        }
        if (creditClientRepository.existsByPumpId(pumpId) || tankerDeliveryRepository.existsByPumpId(pumpId)) {
            throw new BusinessException(HAS_DATA_MESSAGE);
        }

        for (DispensaryUnit du : duRepository.findByPumpIdOrderByDuNumberAsc(pumpId)) {
            nozzleRepository.deleteAll(nozzleRepository.findByDuIdOrderByNozzleNumberAsc(du.getId()));
            duRepository.delete(du);
        }
        tankRepository.deleteAll(tankRepository.findByPumpId(pumpId));
        pumpLocationRepository.delete(pump);
        try {
            pumpLocationRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Pump {} delete refused by remaining references: {}", pumpId, e.getMostSpecificCause().getMessage());
            throw new BusinessException(HAS_DATA_MESSAGE);
        }
        log.info("Pump deleted: pump={}, owner={}", pumpId, currentUser.getId());
    }
}
