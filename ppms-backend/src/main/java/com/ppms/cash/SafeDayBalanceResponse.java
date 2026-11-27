package com.ppms.cash;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The ledger balance a safe count for that business day is compared with. */
public record SafeDayBalanceResponse(LocalDate date, BigDecimal balance) {}
