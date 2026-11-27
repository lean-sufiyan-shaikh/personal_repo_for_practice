package com.ppms.fleet;

import com.ppms.credit.VehicleCheckMode;

import java.util.List;

/**
 * The vehicles a client's screens show, with the account's check. For a parent this is every vehicle of the group;
 * for a sub-account, its own vehicles and those of its parent it may use. The picker lists only active ones.
 *
 * @param checkClientId   the client whose setting the check is: the parent for a sub-account
 * @param checkClientName that client's name
 */
public record ClientVehiclesResponse(VehicleCheckMode vehicleCheck, Long checkClientId, String checkClientName,
                                     List<ClientVehicleResponse> vehicles) {}
