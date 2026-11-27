package com.ppms.fleet;

import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductQueryService;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.credit.CreditClient;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.transaction.TransactionPaymentMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What each vehicle of an account took on credit (active fuel entries and counter credit sales). An entry counts for
 * the vehicle it was recorded against, or else for the account's vehicle whose registration it names once
 * normalised (entries written before vehicles were listed); the rest are unassigned. A sub-account's entries may
 * use its parent's vehicles too. Four queries per call.
 */
@Service
@RequiredArgsConstructor
public class VehicleUsageService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final LocalDate NO_START = LocalDate.of(1900, 1, 1);
    private static final LocalDate NO_END = LocalDate.of(9999, 12, 31);

    private final ClientVehicleRepository vehicleRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final AncillaryProductRepository productRepository;

    private record Use(Long vehicleId, String enteredRegistration, VehicleUsageGroup.Line line) {}

    /**
     * Vehicles in registration order, then the unassigned group; groups without any credit are left out.
     * A null date leaves that end of the period open.
     */
    @Transactional(readOnly = true)
    public List<VehicleUsageGroup> usageByVehicle(Collection<CreditClient> accountClients, LocalDate fromDate,
                                                  LocalDate toDate) {
        Map<Long, String> accountNameById = accountClients.stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName, (first, second) -> first));
        Set<Long> vehicleOwnerIds = new HashSet<>(accountNameById.keySet());
        accountClients.stream().map(CreditClient::getParentClientId).filter(Objects::nonNull).forEach(vehicleOwnerIds::add);
        List<ClientVehicle> vehicles = vehicleRepository.findByClientIdInOrderByRegistrationAsc(vehicleOwnerIds);
        Map<String, Long> vehicleIdByRegistration = vehicleIdByRegistration(vehicles);

        Map<Long, List<VehicleUsageGroup.Line>> linesByVehicleId = new LinkedHashMap<>();
        vehicles.forEach(vehicle -> linesByVehicleId.put(vehicle.getId(), new ArrayList<>()));
        List<VehicleUsageGroup.Line> unassigned = new ArrayList<>();
        for (Use use : usesOf(accountNameById, Objects.requireNonNullElse(fromDate, NO_START),
                Objects.requireNonNullElse(toDate, NO_END))) {
            Long vehicleId = use.vehicleId() != null && linesByVehicleId.containsKey(use.vehicleId())
                    ? use.vehicleId()
                    : vehicleIdByRegistration.get(Objects.requireNonNullElse(
                            VehicleRegistrationNumber.normalise(use.enteredRegistration()), ""));
            (vehicleId != null ? linesByVehicleId.get(vehicleId) : unassigned).add(use.line());
        }

        Map<Long, ClientVehicle> vehicleById = vehicles.stream()
                .collect(Collectors.toMap(ClientVehicle::getId, Function.identity()));
        List<VehicleUsageGroup> groups = new ArrayList<>();
        linesByVehicleId.forEach((vehicleId, lines) -> {
            if (!lines.isEmpty()) {
                groups.add(groupOf(vehicleId, vehicleById.get(vehicleId).getRegistration(), lines));
            }
        });
        if (!unassigned.isEmpty()) {
            groups.add(groupOf(null, null, unassigned));
        }
        return groups;
    }

    /** Litres and amount each vehicle of the account took in the month, by vehicle id. */
    @Transactional(readOnly = true)
    public Map<Long, VehicleUsageGroup> monthUsageByVehicleId(Collection<CreditClient> accountClients, YearMonth month) {
        return usageByVehicle(accountClients, month.atDay(1), month.atEndOfMonth()).stream()
                .filter(group -> !group.isUnassigned())
                .collect(Collectors.toMap(VehicleUsageGroup::vehicleId, Function.identity()));
    }

    private List<Use> usesOf(Map<Long, String> accountNameById, LocalDate fromDate, LocalDate toDate) {
        List<Use> uses = new ArrayList<>();
        for (ShiftCreditEntry entry : creditEntryRepository.findActiveByClientIdInAndSaleDateBetween(
                accountNameById.keySet(), fromDate, toDate)) {
            uses.add(new Use(entry.getVehicleId(), entry.getVehicleRegistration(), new VehicleUsageGroup.Line(
                    entry.getSaleDate(), accountNameById.get(entry.getClientId()), entry.getFuelType(),
                    entry.getVehicleRegistration(), entry.getDriverName(), entry.getLitres(), entry.getAmount())));
        }
        List<AncillarySale> counterSales = ancillarySaleRepository.findByClientIdsAndPaymentModeAndSaleDateBetween(
                accountNameById.keySet(), TransactionPaymentMode.CREDIT, fromDate, toDate);
        Map<Long, AncillaryProduct> productById = productRepository.findAllById(counterSales.stream()
                        .map(AncillarySale::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AncillaryProduct::getId, Function.identity()));
        for (AncillarySale sale : counterSales) {
            AncillaryProduct product = productById.get(sale.getProductId());
            String item = (product != null ? AncillaryProductQueryService.buildDisplayName(product) : "Product")
                    + " × " + sale.getQuantityUnits();
            uses.add(new Use(sale.getVehicleId(), sale.getVehicleRegistration(), new VehicleUsageGroup.Line(
                    sale.getSaleDate(), accountNameById.get(sale.getClientId()), item, sale.getVehicleRegistration(),
                    sale.getDriverName(), null, sale.getTotalWithGst())));
        }
        uses.sort(Comparator.comparing((Use use) -> use.line().date()));
        return uses;
    }

    /** An active vehicle wins over deactivated ones with the same registration, then the newest. */
    private static Map<String, Long> vehicleIdByRegistration(List<ClientVehicle> vehicles) {
        Map<String, ClientVehicle> byRegistration = new HashMap<>();
        Comparator<ClientVehicle> preferred = Comparator.comparing(ClientVehicle::isActive)
                .thenComparing(ClientVehicle::getId);
        vehicles.forEach(vehicle -> byRegistration.merge(vehicle.getRegistration(), vehicle,
                (first, second) -> preferred.compare(first, second) >= 0 ? first : second));
        Map<String, Long> idByRegistration = new HashMap<>();
        byRegistration.forEach((registration, vehicle) -> idByRegistration.put(registration, vehicle.getId()));
        return idByRegistration;
    }

    private static VehicleUsageGroup groupOf(Long vehicleId, String registration, List<VehicleUsageGroup.Line> lines) {
        BigDecimal litres = lines.stream().map(VehicleUsageGroup.Line::litres).filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = lines.stream().map(VehicleUsageGroup.Line::amount)
                .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new VehicleUsageGroup(vehicleId, registration, litres, amount, List.copyOf(lines));
    }
}
