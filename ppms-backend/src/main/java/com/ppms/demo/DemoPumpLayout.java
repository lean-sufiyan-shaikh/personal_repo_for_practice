package com.ppms.demo;

import com.ppms.ancillary.AncillaryProduct;
import com.ppms.credit.CreditClient;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.Nozzle;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.UndergroundTank;
import com.ppms.user.User;

import java.util.List;

/** What the demo pump was set up with, for the sample activity to run on. */
record DemoPumpLayout(PumpLocation pump, UndergroundTank petrolTank, UndergroundTank dieselTank,
                      List<DemoDispenser> dispensers, PumpShiftDefinition dayShift, PumpShiftDefinition nightShift,
                      User manager, List<User> dayOperators, List<User> nightOperators,
                      CreditClient fleetClient, CreditClient fleetSubAccount, CreditClient farmClient,
                      CreditClient busDepotClient, List<AncillaryProduct> products) {

    Long pumpId() {
        return pump.getId();
    }

    /** One dispenser with its petrol and diesel nozzle. */
    record DemoDispenser(DispensaryUnit du, Nozzle petrolNozzle, Nozzle dieselNozzle) {
    }
}
