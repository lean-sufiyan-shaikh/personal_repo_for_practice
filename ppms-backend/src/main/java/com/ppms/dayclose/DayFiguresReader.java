package com.ppms.dayclose;

import com.ppms.attention.AttentionItem;
import com.ppms.attention.AttentionService;
import com.ppms.attention.AttentionType;
import com.ppms.cash.BankDepositRepository;
import com.ppms.cash.CashDropRepository;
import com.ppms.cash.CashDropStatus;
import com.ppms.cash.SafeCountRepository;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditAgingService;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.fuel.FuelType;
import com.ppms.fuel.GlobalFuelPrice;
import com.ppms.fuel.GlobalFuelPriceRepository;
import com.ppms.inventory.DipCheckRepository;
import com.ppms.inventory.ShortageClaimRepository;
import com.ppms.inventory.ShortageClaimStatus;
import com.ppms.inventory.TankerDeliveryRepository;
import com.ppms.inventory.WetStockReconciliationService;
import com.ppms.pettycash.PettyCashBox;
import com.ppms.pettycash.PettyCashBoxRepository;
import com.ppms.pettycash.PettyCashCountRepository;
import com.ppms.pettycash.PettyCashEntryRepository;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.report.DaySalesReader;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads the figures of one business day for its close, each kind of record in a fixed number of queries. */
@Component
@RequiredArgsConstructor
class DayFiguresReader {

    private final ShiftRepository shiftRepository;
    private final SafeCountRepository safeCountRepository;
    private final GlobalFuelPriceRepository fuelPriceRepository;
    private final CashDropRepository cashDropRepository;
    private final BankDepositRepository bankDepositRepository;
    private final TankerDeliveryRepository deliveryRepository;
    private final ShortageClaimRepository claimRepository;
    private final UndergroundTankRepository tankRepository;
    private final DipCheckRepository dipCheckRepository;
    private final WetStockReconciliationService reconciliationService;
    private final DaySalesReader daySalesReader;
    private final CreditPaymentRepository creditPaymentRepository;
    private final CreditAgingService creditAgingService;
    private final AttentionService attentionService;
    private final DayPendingApprovalRepository pendingApprovalRepository;
    private final PettyCashBoxRepository pettyCashBoxRepository;
    private final PettyCashEntryRepository pettyCashEntryRepository;
    private final PettyCashCountRepository pettyCashCountRepository;
    private final BusinessClock businessClock;

    DayFigures read(Long pumpId, LocalDate date, User currentUser) {
        OffsetDateTime dayStart = businessClock.startOfDay(date);
        OffsetDateTime nextDayStart = businessClock.startOfDay(date.plusDays(1));
        List<Shift> closedShifts = shiftRepository.findClosedShiftsByDate(pumpId, date);
        List<UndergroundTank> activeTanks = tankRepository.findByPumpId(pumpId).stream()
                .filter(tank -> tank.getStatus() == TankStatus.ACTIVE)
                .toList();
        CashDropRepository.DropTotal pending = cashDropRepository.totalForPump(pumpId, CashDropStatus.PENDING);
        CashDropRepository.DropTotal confirmed = cashDropRepository.totalReviewedBetween(
                pumpId, CashDropStatus.CONFIRMED, dayStart, nextDayStart);
        BankDepositRepository.DepositTotal deposits = bankDepositRepository.totalForList(pumpId, date, date, null);
        return new DayFigures(
                date,
                closedShifts,
                shiftRepository.findOpenShiftsByDate(pumpId, date),
                safeCountRepository.findFirstByPumpIdAndCountForDateOrderByCountedAtDescIdDesc(pumpId, date),
                fuelTypesWithoutNextDayPrice(pumpId, date, activeTanks),
                new DayFigures.Total(pending.getDropCount(), money(pending.getAmount())),
                new DayFigures.Total(confirmed.getDropCount(), money(confirmed.getAmount())),
                new DayFigures.Total(deposits.getDepositCount(), money(deposits.getAmount())),
                deliveryRepository.findByPumpIdAndDeliveryDateGreaterThanEqualAndDeliveryDateLessThan(pumpId, dayStart, nextDayStart),
                openClaims(pumpId),
                activeTanks.stream()
                        .filter(tank -> tank.getCurrentStock().signum() > 0)
                        .filter(tank -> tank.getCreatedAt().isBefore(nextDayStart))
                        .toList(),
                new HashSet<>(dipCheckRepository.findTankIdsDippedBetween(pumpId, dayStart, nextDayStart)),
                reconciliationService.reconcile(pumpId, date, date, null),
                daySalesReader.salesOf(pumpId, date, closedShifts.stream().map(Shift::getId).toList()),
                money(creditPaymentRepository.sumAmountByPumpIdAndDate(pumpId, date)),
                creditAgingService.agingOfPump(pumpId).summary(),
                attentionService.itemsForPump(pumpId, currentUser).stream()
                        .filter(item -> item.type() != AttentionType.DAY_CLOSES_UNAPPROVED)
                        .toList(),
                date.isBefore(businessClock.today()),
                total(pendingApprovalRepository.pendingExpensesOn(pumpId, date)),
                total(pendingApprovalRepository.pendingCreditPaymentsOn(pumpId, date)),
                pettyCashInUse(pumpId),
                pettyCashCountRepository.existsByPumpIdAndCountForDate(pumpId, date));
    }

    private boolean pettyCashInUse(Long pumpId) {
        boolean hasFloat = pettyCashBoxRepository.findById(pumpId)
                .map(PettyCashBox::getFloatAmount)
                .filter(amount -> amount.signum() > 0)
                .isPresent();
        return hasFloat || pettyCashEntryRepository.existsByPumpId(pumpId);
    }

    /**
     * Prices take effect when they are saved, so the next day's price of a fuel counts as set once it was saved or
     * confirmed on the next day, or today when the day being closed is today and the next day has not begun.
     */
    private List<FuelType> fuelTypesWithoutNextDayPrice(Long pumpId, LocalDate date, List<UndergroundTank> activeTanks) {
        LocalDate earliestDay = date.isBefore(businessClock.today()) ? date.plusDays(1) : date;
        OffsetDateTime setFrom = businessClock.startOfDay(earliestDay);
        Map<FuelType, GlobalFuelPrice> priceByFuel = fuelPriceRepository.findCurrentPricesForPump(pumpId).stream()
                .collect(Collectors.toMap(GlobalFuelPrice::getFuelType, Function.identity(), (first, second) -> first));
        return activeTanks.stream()
                .map(UndergroundTank::getFuelType)
                .distinct()
                .sorted()
                .filter(fuelType -> {
                    GlobalFuelPrice price = priceByFuel.get(fuelType);
                    return price == null || price.getEffectiveFrom().isBefore(setFrom);
                })
                .toList();
    }

    private DayFigures.Total openClaims(Long pumpId) {
        return claimRepository.totalsByStatus(pumpId).stream()
                .filter(totals -> totals.getStatus() == ShortageClaimStatus.RAISED)
                .findFirst()
                .map(totals -> new DayFigures.Total(totals.getClaimCount(), money(totals.getClaimAmount())))
                .orElse(new DayFigures.Total(0, money(BigDecimal.ZERO)));
    }

    private static DayFigures.Total total(DayPendingApprovalRepository.PendingTotal pending) {
        return new DayFigures.Total(pending.getItemCount(), money(pending.getAmount()));
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount != null ? amount : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
