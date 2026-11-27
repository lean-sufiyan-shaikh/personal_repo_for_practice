package com.ppms.fleet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClientVehicleRepository extends JpaRepository<ClientVehicle, Long> {

    List<ClientVehicle> findByClientIdInOrderByRegistrationAsc(Collection<Long> clientIds);

    Optional<ClientVehicle> findByIdAndPumpId(Long id, Long pumpId);

    boolean existsByPumpIdAndRegistrationAndIsActiveTrueAndIdNot(Long pumpId, String registration, Long id);

    boolean existsByPumpIdAndRegistrationAndIsActiveTrue(Long pumpId, String registration);
}
