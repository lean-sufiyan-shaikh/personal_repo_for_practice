package com.ppms.inventory;

/** Outcome of a delivery's receipt check; OK covers any difference within the pump's tolerance. */
public enum DeliveryCheckStatus {
    NOT_CHECKED,
    OK,
    SHORT,
    OVER
}
