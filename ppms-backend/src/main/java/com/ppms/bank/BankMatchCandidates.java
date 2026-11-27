package com.ppms.bank;

import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.cash.BankDeposit;
import com.ppms.cash.BankDepositRepository;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditPayment;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.settlement.PaymentSettlement;
import com.ppms.settlement.PaymentSettlementRepository;
import com.ppms.settlement.SettlementChargeback;
import com.ppms.settlement.SettlementChargebackRepository;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.supplier.FuelSupplier;
import com.ppms.supplier.FuelSupplierPayment;
import com.ppms.supplier.FuelSupplierPaymentRepository;
import com.ppms.supplier.FuelSupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Loads the records statement lines are matched to, as {@link MatchCandidate}s with their bank-side amount. */
@Component
@RequiredArgsConstructor
class BankMatchCandidates {

    private final BankMatchCandidateRepository candidateRepository;
    private final PaymentSettlementRepository settlementRepository;
    private final BankDepositRepository depositRepository;
    private final FuelSupplierPaymentRepository supplierPaymentRepository;
    private final FuelSupplierRepository supplierRepository;
    private final CreditPaymentRepository creditPaymentRepository;
    private final ShiftRepository shiftRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final SettlementChargebackRepository chargebackRepository;

    /** Settlements, deposits and credit payments for credit lines; supplier payments and chargebacks for debit lines. */
    List<MatchCandidate> open(Long pumpId, boolean creditSide, LocalDate from, LocalDate to,
                              Collection<BankLineMatchStatus> claimingStatuses) {
        if (!creditSide) {
            List<MatchCandidate> debits = new ArrayList<>(
                    supplierPayments(candidateRepository.findOpenSupplierPayments(pumpId, from, to, claimingStatuses)));
            candidateRepository.findOpenChargebacks(pumpId, from, to, claimingStatuses).forEach(c -> debits.add(chargeback(c)));
            return debits;
        }
        List<MatchCandidate> candidates = new ArrayList<>();
        candidateRepository.findOpenSettlements(pumpId, from, to, claimingStatuses).forEach(s -> candidates.add(settlement(s)));
        candidateRepository.findOpenDeposits(pumpId, from, to, claimingStatuses).forEach(d -> candidates.add(deposit(d)));
        candidateRepository.findOpenCreditPayments(pumpId, from, to, ExpenseApprovalStatus.APPROVED, claimingStatuses)
                .forEach(p -> candidates.add(creditPayment(p)));
        return candidates;
    }

    /** The record behind a link, checked to belong to the pump. */
    MatchCandidate require(Long pumpId, BankMatchRef ref) {
        return describe(pumpId, List.of(ref)).values().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(entityTypeOf(ref.type()), ref.type() + " not found"));
    }

    /** Records behind the given links that belong to the pump, keyed by link. */
    Map<BankMatchRef, MatchCandidate> describe(Long pumpId, Collection<BankMatchRef> refs) {
        Map<BankMatchRecordType, Set<Long>> idsByType = refs.stream()
                .collect(Collectors.groupingBy(BankMatchRef::type, Collectors.mapping(BankMatchRef::id, Collectors.toSet())));
        List<MatchCandidate> found = new ArrayList<>();
        idsByType.forEach((type, ids) -> found.addAll(switch (type) {
            case SETTLEMENT -> owned(settlementRepository.findAllById(ids), PaymentSettlement::getPumpId, pumpId)
                    .stream().map(this::settlement).toList();
            case DEPOSIT -> owned(depositRepository.findAllById(ids), BankDeposit::getPumpId, pumpId)
                    .stream().map(this::deposit).toList();
            case SUPPLIER_PAYMENT -> supplierPayments(owned(supplierPaymentRepository.findAllById(ids),
                    FuelSupplierPayment::getPumpId, pumpId));
            case CHARGEBACK -> owned(chargebackRepository.findAllById(ids), SettlementChargeback::getPumpId, pumpId)
                    .stream().map(this::chargeback).toList();
            case CREDIT_PAYMENT -> owned(creditPaymentRepository.findAllById(ids), CreditPayment::getPumpId, pumpId)
                    .stream().map(this::creditPayment).toList();
            case SHIFT -> owned(shiftRepository.findAllById(ids), Shift::getPumpId, pumpId).stream().map(this::shift).toList();
            case ANCILLARY_SALE -> ancillarySaleRepository.findAllById(ids).stream()
                    .filter(sale -> sale.getPumpId().equals(pumpId))
                    .map(sale -> new MatchCandidate(BankMatchRecordType.ANCILLARY_SALE, sale.getId(), sale.getSaleDate(),
                            money(sale.getTotalWithGst()), null, null))
                    .toList();
        }));
        return found.stream().collect(Collectors.toMap(MatchCandidate::ref, Function.identity()));
    }

    private static <T> List<T> owned(List<T> records, Function<T, Long> pumpIdOf, Long pumpId) {
        return records.stream().filter(record -> pumpId.equals(pumpIdOf.apply(record))).toList();
    }

    private MatchCandidate settlement(PaymentSettlement s) {
        return new MatchCandidate(BankMatchRecordType.SETTLEMENT, s.getId(), s.getSettlementDate(), money(s.getNetAmount()),
                s.getBankReference(), s.getPaymentType().name());
    }

    private MatchCandidate deposit(BankDeposit d) {
        return new MatchCandidate(BankMatchRecordType.DEPOSIT, d.getId(), d.getDepositDate(), money(d.getAmount()),
                d.getSlipReference(), d.getBankAccount());
    }

    private MatchCandidate chargeback(SettlementChargeback c) {
        return new MatchCandidate(BankMatchRecordType.CHARGEBACK, c.getId(), c.getChargebackDate(), money(c.getAmount()),
                c.getReference(), c.getKind() + " " + c.getPaymentType());
    }

    private MatchCandidate creditPayment(CreditPayment p) {
        return new MatchCandidate(BankMatchRecordType.CREDIT_PAYMENT, p.getId(), p.getPaidAt(), money(p.getAmount()),
                p.getReferenceNo(), p.getPaymentMode() != null ? p.getPaymentMode().name() : null);
    }

    private MatchCandidate shift(Shift shift) {
        BigDecimal digital = orZero(shift.getUpiCollected()).add(orZero(shift.getCardCollected()))
                .add(orZero(shift.getFleetCardCollected()));
        return new MatchCandidate(BankMatchRecordType.SHIFT, shift.getId(), shift.getShiftDate(), money(digital), null, null);
    }

    private List<MatchCandidate> supplierPayments(List<FuelSupplierPayment> payments) {
        Set<Long> supplierIds = payments.stream().map(FuelSupplierPayment::getSupplierId).collect(Collectors.toSet());
        Map<Long, String> supplierNames = supplierIds.isEmpty() ? Map.of()
                : supplierRepository.findAllById(supplierIds).stream()
                        .collect(Collectors.toMap(FuelSupplier::getId, FuelSupplier::getName, (a, b) -> a, HashMap::new));
        return payments.stream()
                .map(p -> new MatchCandidate(BankMatchRecordType.SUPPLIER_PAYMENT, p.getId(), p.getPaymentDate(),
                        money(p.getAmount()), p.getReferenceNo(), supplierNames.get(p.getSupplierId())))
                .toList();
    }

    private static EntityType entityTypeOf(BankMatchRecordType type) {
        return switch (type) {
            case SETTLEMENT -> EntityType.SETTLEMENT;
            case DEPOSIT -> EntityType.BANK_DEPOSIT;
            case SUPPLIER_PAYMENT -> EntityType.PAYMENT;
            case CHARGEBACK -> EntityType.CHARGEBACK;
            case CREDIT_PAYMENT -> EntityType.CREDIT_PAYMENT;
            case SHIFT -> EntityType.SHIFT;
            case ANCILLARY_SALE -> EntityType.SALE;
        };
    }

    private static BigDecimal money(BigDecimal amount) {
        return orZero(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }
}
