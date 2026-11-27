package com.ppms.adjustment;

public enum MeterAdjustmentStatus {
    APPLIED,
    /** A manager's forward adjustment, waiting for an Owner or Admin; the nozzle is unchanged until then. */
    PENDING_APPROVAL,
    REJECTED
}
