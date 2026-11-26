package com.ppms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryNozzleReadingRepository extends JpaRepository<DeliveryNozzleReading, Long> {

    List<DeliveryNozzleReading> findByTankerDeliveryIdOrderByNozzleIdAsc(Long tankerDeliveryId);
}
