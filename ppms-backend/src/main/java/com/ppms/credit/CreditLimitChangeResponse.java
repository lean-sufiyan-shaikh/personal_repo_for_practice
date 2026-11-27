package com.ppms.credit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CreditLimitChangeResponse(Long id, BigDecimal oldLimit, BigDecimal newLimit, boolean oldUnlimited,
                                        boolean newUnlimited, String reason, String changedByName,
                                        OffsetDateTime changedAt) {

    static CreditLimitChangeResponse of(CreditLimitChange change, String changedByName) {
        return new CreditLimitChangeResponse(change.getId(), change.getOldLimit(), change.getNewLimit(),
                change.isOldUnlimited(), change.isNewUnlimited(), change.getReason(), changedByName,
                change.getChangedAt());
    }
}
