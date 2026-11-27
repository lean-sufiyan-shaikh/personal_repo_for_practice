package com.ppms.radar;

/** How much a score is worth a look: below 30 Low, 30 to 59 Watch, 60 and above High. */
public enum RadarBand {
    LOW,
    WATCH,
    HIGH,
    NOT_ENOUGH_DATA;

    static final int WATCH_FROM = 30;
    static final int HIGH_FROM = 60;

    static RadarBand of(int score) {
        if (score >= HIGH_FROM) {
            return HIGH;
        }
        return score >= WATCH_FROM ? WATCH : LOW;
    }

    boolean isWorthALook() {
        return this == WATCH || this == HIGH;
    }
}
