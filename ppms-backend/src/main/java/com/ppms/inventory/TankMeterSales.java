package com.ppms.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Litres a tank's nozzles dispensed between two moments, read from meter readings rather than from which day a shift
 * is dated. Each shift nozzle is a {@link MeterTimeline}: the litres it had dispensed since its shift opened, known at
 * the opening, at every dip taken while the shift was open and at the close.
 */
final class TankMeterSales {

    private final List<MeterTimeline> timelines;

    TankMeterSales(List<MeterTimeline> timelines) {
        this.timelines = List.copyOf(timelines);
    }

    static TankMeterSales none() {
        return new TankMeterSales(List.of());
    }

    BigDecimal soldBetween(OffsetDateTime from, OffsetDateTime to) {
        BigDecimal sold = BigDecimal.ZERO;
        for (MeterTimeline timeline : timelines) {
            sold = sold.add(timeline.dispensedBy(to).subtract(timeline.dispensedBy(from)));
        }
        return sold;
    }

    /**
     * Litres dispensed since the shift opened, known at some moments. Between two known moments the last known figure
     * stands, which is exact for a nozzle that was idle and, for a shift open across a dip, only misses what the
     * dip's own meter snapshot would have recorded.
     */
    static final class MeterTimeline {

        private final List<Point> points = new ArrayList<>();

        MeterTimeline(OffsetDateTime openedAt) {
            points.add(new Point(openedAt, BigDecimal.ZERO));
        }

        MeterTimeline knownAt(OffsetDateTime at, BigDecimal dispensedSinceOpen) {
            points.add(new Point(at, dispensedSinceOpen));
            points.sort(Comparator.comparing(Point::at));
            return this;
        }

        BigDecimal dispensedBy(OffsetDateTime moment) {
            BigDecimal dispensed = BigDecimal.ZERO;
            for (Point point : points) {
                if (point.at().isAfter(moment)) {
                    break;
                }
                dispensed = point.dispensedSinceOpen();
            }
            return dispensed;
        }

        private record Point(OffsetDateTime at, BigDecimal dispensedSinceOpen) {
        }
    }
}
