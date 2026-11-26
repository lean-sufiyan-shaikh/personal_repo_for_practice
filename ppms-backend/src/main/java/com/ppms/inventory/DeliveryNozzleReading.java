package com.ppms.inventory;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** A nozzle's meter readings taken with the dips before and after a delivery was unloaded. */
@Entity
@Table(name = "delivery_nozzle_readings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryNozzleReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tanker_delivery_id", nullable = false)
    private Long tankerDeliveryId;

    @Column(name = "nozzle_id", nullable = false)
    private Long nozzleId;

    @Column(name = "reading_before", nullable = false)
    private BigDecimal readingBefore;

    @Column(name = "reading_after", nullable = false)
    private BigDecimal readingAfter;

    @Column(name = "litres_sold", nullable = false)
    private BigDecimal litresSold;
}
