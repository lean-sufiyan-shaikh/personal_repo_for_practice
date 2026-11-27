package com.ppms.credit;

/**
 * What happens to a credit entry for a vehicle outside the account's approved list, for a fuel the vehicle may not
 * take, or past the vehicle's monthly limit: OFF allows it as before, WARN allows it but flags it and tells the
 * managers, BLOCK refuses it.
 */
public enum VehicleCheckMode {
    OFF,
    WARN,
    BLOCK
}
