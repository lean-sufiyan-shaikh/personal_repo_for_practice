package com.ppms.fuel;

import lombok.Builder;
import lombok.Value;

/**
 * Response for POST /api/pumps/{pumpId}/fuel-prices.
 *
 * Contains the newly created price record and how many open nozzles selling the fuel were split at the readings
 * entered: their litres up to that reading keep the old price, later litres take the new one.
 */
@Value
@Builder
public class SetFuelPriceResponse {

    GlobalFuelPrice price;

    /** Open nozzles selling this fuel whose litres are now priced in two segments. */
    int splitNozzleCount;
}
