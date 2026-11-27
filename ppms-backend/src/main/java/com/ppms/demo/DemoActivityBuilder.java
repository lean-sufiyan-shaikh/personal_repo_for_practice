package com.ppms.demo;

import com.ppms.ancillary.AncillaryInventoryWorkflowService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.BackfillSaleRequest;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditLedgerService;
import com.ppms.credit.PaymentMode;
import com.ppms.credit.RecordPaymentRequest;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.expense.ExpenseCategory;
import com.ppms.expense.PumpExpense;
import com.ppms.expense.PumpExpenseRepository;
import com.ppms.inventory.DipCheck;
import com.ppms.inventory.DipCheckRepository;
import com.ppms.inventory.DipCheckStatus;
import com.ppms.inventory.InventoryWorkflowService;
import com.ppms.inventory.ReceiptCheckRequest;
import com.ppms.inventory.RecordDeliveryRequest;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpShiftDefinition;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.shift.BackfillShiftRequest;
import com.ppms.shift.CloseShiftRequest;
import com.ppms.shift.ShiftBackfillService;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Runs {@link #DAYS} days of sample business on the demo pump through the same services staff use: backfilled day
 * and night shifts on both dispensers with cash, UPI, card and credit sales, one tanker delivery per tank, counter
 * sales, expenses, a client payment and an end-of-day dip per tank. The figures come from a random generator seeded
 * with the pump id, so they look varied but always add up.
 */
@Component
@RequiredArgsConstructor
class DemoActivityBuilder {

    static final int DAYS = 14;
    private static final int DELIVERY_DAYS_AGO = 7;
    private static final int SHORT_SHIFT_DAYS_AGO = 3;

    private final ShiftBackfillService shiftBackfillService;
    private final InventoryWorkflowService inventoryWorkflowService;
    private final AncillaryInventoryWorkflowService ancillaryWorkflowService;
    private final CreditLedgerService creditLedgerService;
    private final PumpExpenseRepository expenseRepository;
    private final DipCheckRepository dipCheckRepository;
    private final NozzleRepository nozzleRepository;
    private final UndergroundTankRepository tankRepository;
    private final BusinessClock businessClock;

    void run(DemoPumpLayout layout, User actor) {
        Random random = new Random(layout.pumpId());
        Map<Long, BigDecimal> readingByNozzleId = new HashMap<>();
        layout.dispensers().forEach(dispenser -> {
            readingByNozzleId.put(dispenser.petrolNozzle().getId(), dispenser.petrolNozzle().getLastReading());
            readingByNozzleId.put(dispenser.dieselNozzle().getId(), dispenser.dieselNozzle().getLastReading());
        });

        LocalDate today = businessClock.today();
        for (int daysAgo = DAYS; daysAgo >= 1; daysAgo--) {
            LocalDate day = today.minusDays(daysAgo);
            if (daysAgo == DELIVERY_DAYS_AGO) {
                recordDelivery(layout, layout.petrolTank(), "8000", "97.10", day, readingByNozzleId, actor);
                recordDelivery(layout, layout.dieselTank(), "10000", "84.35", day, readingByNozzleId, actor);
            }
            for (int index = 0; index < layout.dispensers().size(); index++) {
                DemoPumpLayout.DemoDispenser dispenser = layout.dispensers().get(index);
                backfillShift(layout, dispenser, layout.dayShift(), layout.dayOperators().get(index), day,
                        index == 0 && daysAgo % 2 == 0 ? layout.fleetClient() : null, false, random, readingByNozzleId, actor);
                backfillShift(layout, dispenser, layout.nightShift(), layout.nightOperators().get(index), day,
                        index == 1 && daysAgo % 3 == 0 ? layout.busDepotClient() : null,
                        index == 1 && daysAgo == SHORT_SHIFT_DAYS_AGO, random, readingByNozzleId, actor);
            }
            sellCounterProducts(layout, day, daysAgo, random, actor);
            recordExpenses(layout, day, daysAgo, actor);
            recordClosingDips(layout, day);
        }
        recordClientPayment(layout, today.minusDays(2), actor);
        saveMeterReadings(layout, readingByNozzleId);
    }

    private void backfillShift(DemoPumpLayout layout, DemoPumpLayout.DemoDispenser dispenser, PumpShiftDefinition shift,
                               User operator, LocalDate day, CreditClient creditClient, boolean cashShort,
                               Random random, Map<Long, BigDecimal> readingByNozzleId, User actor) {
        BigDecimal petrolLitres = BigDecimal.valueOf(180 + random.nextInt(121));
        BigDecimal dieselLitres = BigDecimal.valueOf(220 + random.nextInt(161));
        BigDecimal petrolPrice = petrolPriceOn(day);
        BigDecimal amountDue = petrolLitres.multiply(petrolPrice).add(dieselLitres.multiply(DemoPumpBuilder.DIESEL_PRICE))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal upi = wholeRupees(amountDue.multiply(new BigDecimal("0.30")));
        BigDecimal card = wholeRupees(amountDue.multiply(new BigDecimal("0.12")));
        List<CloseShiftRequest.CreditEntryRequest> creditEntries = new ArrayList<>();
        if (creditClient != null) {
            BigDecimal creditAmount = wholeRupees(DemoPumpBuilder.DIESEL_PRICE.multiply(BigDecimal.valueOf(20 + random.nextInt(30))));
            creditEntries.add(new CloseShiftRequest.CreditEntryRequest(creditClient.getName(), creditClient.getId(),
                    "B-" + day.getDayOfMonth() + dispenser.du().getDuNumber(), creditAmount, "DIESEL", null,
                    "MH12AB" + (1000 + random.nextInt(9000)), null, null, null));
        }
        BigDecimal creditTotal = creditEntries.stream().map(CloseShiftRequest.CreditEntryRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shortfall = cashShort ? new BigDecimal("150.00") : BigDecimal.ZERO;

        BackfillShiftRequest request = new BackfillShiftRequest();
        request.setShiftDefinitionId(shift.getId());
        request.setShiftDate(day);
        request.setDuId(dispenser.du().getId());
        request.setOperatorId(operator.getId());
        request.setNozzleReadings(List.of(
                readingFor(dispenser.petrolNozzle(), petrolLitres, readingByNozzleId),
                readingFor(dispenser.dieselNozzle(), dieselLitres, readingByNozzleId)));
        request.setUpiCollected(upi);
        request.setCardCollected(card);
        request.setFleetCardCollected(BigDecimal.ZERO);
        request.setCreditTotal(creditTotal);
        request.setCreditEntries(creditEntries);
        request.setCashCollected(amountDue.subtract(upi).subtract(card).subtract(creditTotal).subtract(shortfall));
        if (cashShort) {
            request.setDiscrepancyReason("Cash counted short at handover");
        }
        shiftBackfillService.backfillShift(layout.pumpId(), request, actor);
    }

    private static BackfillShiftRequest.NozzleReadingRequest readingFor(Nozzle nozzle, BigDecimal litres,
                                                                         Map<Long, BigDecimal> readingByNozzleId) {
        BigDecimal opening = readingByNozzleId.get(nozzle.getId());
        BigDecimal closing = opening.add(litres);
        readingByNozzleId.put(nozzle.getId(), closing);
        return new BackfillShiftRequest.NozzleReadingRequest(nozzle.getId(), opening, closing);
    }

    private BigDecimal petrolPriceOn(LocalDate day) {
        return day.isBefore(businessClock.today().minusDays(DemoPumpBuilder.PRICE_REVISION_DAYS_AGO))
                ? DemoPumpBuilder.PETROL_PRICE
                : DemoPumpBuilder.PETROL_PRICE_AFTER_REVISION;
    }

    /** The tank is dipped before and after unloading and every nozzle on it reads the same at both, so nothing is short. */
    private void recordDelivery(DemoPumpLayout layout, UndergroundTank tank, String litres, String cost, LocalDate day,
                                Map<Long, BigDecimal> readingByNozzleId, User actor) {
        BigDecimal stock = tankRepository.findById(tank.getId()).orElseThrow().getCurrentStock();
        List<ReceiptCheckRequest.NozzleReading> readings = layout.dispensers().stream()
                .flatMap(dispenser -> Stream.of(dispenser.petrolNozzle(), dispenser.dieselNozzle()))
                .filter(nozzle -> tank.getId().equals(nozzle.getTankId()))
                .map(nozzle -> new ReceiptCheckRequest.NozzleReading(nozzle.getId(),
                        readingByNozzleId.get(nozzle.getId()), readingByNozzleId.get(nozzle.getId())))
                .toList();
        RecordDeliveryRequest request = new RecordDeliveryRequest();
        request.setTankId(tank.getId());
        request.setQuantityDelivered(new BigDecimal(litres));
        request.setCostPricePerUnit(new BigDecimal(cost));
        request.setDeliveryDate(day);
        request.setInvoiceReference("DEMO-" + tank.getFuelType() + "-" + day);
        request.setReceiptCheck(new ReceiptCheckRequest(stock, stock.add(new BigDecimal(litres)), readings, null, null));
        inventoryWorkflowService.recordDelivery(layout.pumpId(), request, actor);
    }

    private void sellCounterProducts(DemoPumpLayout layout, LocalDate day, int daysAgo, Random random, User actor) {
        AncillaryProduct product = layout.products().get(daysAgo % layout.products().size());
        BackfillSaleRequest sale = new BackfillSaleRequest();
        sale.setProductId(product.getId());
        sale.setSaleDate(day);
        sale.setQuantityUnits(1 + random.nextInt(2));
        if (daysAgo == 9) {
            sale.setPaymentMode(TransactionPaymentMode.CREDIT);
            sale.setClientId(layout.farmClient().getId());
        } else {
            sale.setPaymentMode(daysAgo % 2 == 0 ? TransactionPaymentMode.UPI : TransactionPaymentMode.CASH);
        }
        ancillaryWorkflowService.backfillSale(layout.pumpId(), sale, actor);
    }

    private void recordExpenses(DemoPumpLayout layout, LocalDate day, int daysAgo, User actor) {
        List<PumpExpense> expenses = new ArrayList<>();
        expenses.add(expense(layout, ExpenseCategory.OTHER, "250.00", "Tea and snacks for staff", day, actor));
        if (daysAgo == 10) {
            expenses.add(expense(layout, ExpenseCategory.UTILITIES, "4200.00", "Electricity bill", day, actor));
        }
        if (daysAgo == 5) {
            expenses.add(expense(layout, ExpenseCategory.MAINTENANCE, "1800.00", "Dispenser filter replaced", day, actor));
        }
        expenseRepository.saveAll(expenses);
    }

    private PumpExpense expense(DemoPumpLayout layout, ExpenseCategory category, String amount, String description,
                                LocalDate day, User actor) {
        return PumpExpense.builder()
                .pumpId(layout.pumpId())
                .category(category)
                .amount(new BigDecimal(amount))
                .description(description)
                .expenseDate(day)
                .recordedByUserId(layout.manager().getId())
                .approvalStatus(ExpenseApprovalStatus.APPROVED)
                .approvedByUserId(actor.getId())
                .approvedAt(businessClock.startOfDay(day).plusHours(20))
                .submittedByUserId(layout.manager().getId())
                .submittedAt(businessClock.startOfDay(day).plusHours(19))
                .build();
    }

    /** The closing dip matches the book stock, so the wet-stock check shows a clean fortnight to compare against. */
    private void recordClosingDips(DemoPumpLayout layout, LocalDate day) {
        for (UndergroundTank tank : List.of(layout.petrolTank(), layout.dieselTank())) {
            BigDecimal stock = tankRepository.findById(tank.getId()).orElseThrow().getCurrentStock();
            dipCheckRepository.save(DipCheck.builder()
                    .pumpId(layout.pumpId())
                    .tankId(tank.getId())
                    .measuredQuantity(stock)
                    .systemStock(stock)
                    .notes("Closing dip")
                    .checkedAt(businessClock.startOfDay(day).with(LocalTime.of(23, 0)))
                    .loggedByUserId(layout.manager().getId())
                    .checkedByUserId(layout.nightOperators().get(0).getId())
                    .status(DipCheckStatus.WITHIN_TOLERANCE)
                    .build());
        }
    }

    private void recordClientPayment(DemoPumpLayout layout, LocalDate day, User actor) {
        RecordPaymentRequest payment = new RecordPaymentRequest();
        payment.setAmount(new BigDecimal("5000.00"));
        payment.setPaymentMode(PaymentMode.BANK_TRANSFER);
        payment.setPaidAt(day);
        payment.setReferenceNo("NEFT-DEMO");
        creditLedgerService.recordPayment(layout.pumpId(), layout.busDepotClient().getId(), payment, actor);
    }

    private void saveMeterReadings(DemoPumpLayout layout, Map<Long, BigDecimal> readingByNozzleId) {
        List<Nozzle> nozzles = nozzleRepository.findAllById(readingByNozzleId.keySet());
        nozzles.forEach(nozzle -> nozzle.setLastReading(readingByNozzleId.get(nozzle.getId())));
        nozzleRepository.saveAll(nozzles);
    }

    private static BigDecimal wholeRupees(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.DOWN).setScale(2, RoundingMode.UNNECESSARY);
    }
}
