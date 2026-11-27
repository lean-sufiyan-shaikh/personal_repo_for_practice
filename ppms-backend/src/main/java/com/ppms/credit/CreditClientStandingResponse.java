package com.ppms.credit;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;

/**
 * What the credit client picker needs to grey out clients a sale would be refused for. Operators read this,
 * so it carries no balance or limit beyond the amount still available.
 *
 * @param availableCredit null when the client's group has unlimited credit
 * @param nearLimit       the group has used at least the pump's near-limit share of its limit, whatever its status;
 *                        kept off the picker, which reads NEAR_LIMIT from the status
 */
public record CreditClientStandingResponse(Long clientId, CreditStatus creditStatus, BigDecimal availableCredit,
                                           @JsonIgnore boolean nearLimit) {
}
