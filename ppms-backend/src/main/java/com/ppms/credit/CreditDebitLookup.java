package com.ppms.credit;

import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Finds a live debit of a pump's credit client by its ledger type and id. */
@Component
@RequiredArgsConstructor
public class CreditDebitLookup {

    private static final String ACTIVE = "ACTIVE";

    private final CreditClientRepository clientRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final CreditPaymentReversalRepository reversalRepository;

    /** @param quantity litres of a fuel sale or units of a counter sale; null for interest and bounce charges */
    public record Debit(CreditDebitType type, Long id, Long clientId, LocalDate date, BigDecimal amount,
                        BigDecimal quantity) {}

    public Debit require(Long pumpId, CreditDebitType type, Long debitId) {
        Debit debit = switch (type) {
            case SALE -> creditEntryRepository.findById(debitId)
                    .filter(entry -> ACTIVE.equals(entry.getVoidStatus()) && entry.getClientId() != null)
                    .map(entry -> new Debit(type, debitId, entry.getClientId(), entry.getSaleDate(), entry.getAmount(),
                            entry.getLitres()))
                    .orElse(null);
            case COUNTER_SALE -> ancillarySaleRepository.findById(debitId)
                    .filter(sale -> sale.getPaymentMode() == TransactionPaymentMode.CREDIT && sale.getClientId() != null)
                    .map(sale -> new Debit(type, debitId, sale.getClientId(), sale.getSaleDate(), sale.getTotalWithGst(),
                            BigDecimal.valueOf(sale.getQuantityUnits())))
                    .orElse(null);
            case INTEREST -> interestChargeRepository.findById(debitId)
                    .filter(charge -> charge.getVoidedAt() == null)
                    .map(charge -> new Debit(type, debitId, charge.getClientId(), charge.getPeriodTo(),
                            charge.getAmount(), null))
                    .orElse(null);
            case BOUNCE_CHARGE -> reversalRepository.findById(debitId)
                    .filter(reversal -> reversal.getBounceCharge() != null)
                    .map(reversal -> new Debit(type, debitId, reversal.getClientId(), reversal.getReversalDate(),
                            reversal.getBounceCharge(), null))
                    .orElse(null);
        };
        if (debit == null || !clientRepository.existsByIdAndPumpId(debit.clientId(), pumpId)) {
            throw new ResourceNotFoundException(EntityType.CREDIT_ENTRY, "Debit not found: " + type + " " + debitId);
        }
        return new Debit(debit.type(), debit.id(), debit.clientId(), debit.date(),
                debit.amount().setScale(2, RoundingMode.HALF_UP), debit.quantity());
    }
}
