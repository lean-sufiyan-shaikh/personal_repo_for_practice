package com.ppms.fleet;

import java.math.BigDecimal;
import java.util.List;

/**
 * One vehicle with what it took on credit this calendar month and what is left of its limits.
 *
 * @param litresLeft null without a litre limit; never below zero
 * @param amountLeft null without a rupee limit; never below zero
 */
public record ClientVehicleResponse(Long id, Long clientId, String clientName, String registration,
                                    List<String> allowedFuelTypes, BigDecimal monthlyLitreLimit,
                                    BigDecimal monthlyAmountLimit, String defaultDriverName, String defaultDriverPhone,
                                    boolean active, BigDecimal litresThisMonth, BigDecimal amountThisMonth,
                                    BigDecimal litresLeft, BigDecimal amountLeft) {}
