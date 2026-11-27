package com.ppms.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Part of a shift nozzle's litres sold at one price. A price change while the shift is open ends the running segment
 * at the meter reading taken then; the first segment starts at the opening reading with the opening price.
 */
@Entity
@Table(name = "shift_reading_price_segments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftReadingPriceSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shift_fuel_reading_id", nullable = false)
    private Long shiftFuelReadingId;

    @Column(name = "start_reading", nullable = false)
    private BigDecimal startReading;

    @Column(name = "price_per_unit", nullable = false)
    private BigDecimal pricePerUnit;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    /** Filled in at close. */
    @Column(name = "units_sold")
    private BigDecimal unitsSold;

    /** Who entered the meter reading at the price change; null for the opening segment. */
    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;
}
