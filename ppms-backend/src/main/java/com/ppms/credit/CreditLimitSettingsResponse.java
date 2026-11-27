package com.ppms.credit;

import com.ppms.pump.PumpLocation;

import java.math.BigDecimal;

/**
 * @param defaultCreditLimit limit a new client starts with; 0 means it takes no credit until a limit is set
 * @param managerLimitCap    highest limit a manager may set
 * @param nearLimitPercent   share of the limit used at which an account shows as near its limit
 */
public record CreditLimitSettingsResponse(BigDecimal defaultCreditLimit, BigDecimal managerLimitCap,
                                          int nearLimitPercent) {

    static CreditLimitSettingsResponse of(PumpLocation pump) {
        return new CreditLimitSettingsResponse(pump.getDefaultCreditLimit(), pump.getManagerCreditLimitCap(),
                pump.getNearLimitPercent());
    }
}
