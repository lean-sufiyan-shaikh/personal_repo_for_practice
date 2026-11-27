package com.ppms.fleet;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClient;
import com.ppms.credit.VehicleCheckMode;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks the vehicle of a credit entry against the account's approved vehicles. A sub-account's entry may use its
 * own vehicles and its parent's; the parent's entry may use any vehicle of the group. The group's check decides what
 * an unknown vehicle, a fuel the vehicle may not take, or an entry past the vehicle's monthly limit (litres and
 * rupees, in the sale's calendar month) does: OFF lets it through unchecked, WARN flags it and tells the managers
 * once a day per account, BLOCK refuses it. Callers hold the account's limit-holder lock, so concurrent entries for
 * the same account cannot both slip under a monthly limit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleCreditCheck {

    private static final int MAX_REGISTRATION_LENGTH = 20;

    private final FleetAccountService fleetAccountService;
    private final ClientVehicleRepository vehicleRepository;
    private final VehicleUsageService usageService;
    private final NotificationDedupService notificationDedupService;
    private final BusinessClock businessClock;

    /**
     * @param fuelType null for a counter sale, which no fuel rule applies to
     * @param litres   null for a counter sale; a fuel entry without a known price counts as no litres
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VehicleCreditDecision check(CreditClient billedClient, VehicleCreditInput input, String fuelType,
                                       BigDecimal amount, BigDecimal litres, LocalDate saleDate,
                                       PendingVehicleUsage pending) {
        FleetAccountService.Account account = fleetAccountService.accountOf(billedClient);
        VehicleCheckMode mode = account.holder().getVehicleCheck();
        String registration = VehicleRegistrationNumber.normalise(input.registration());
        ClientVehicle vehicle = resolveVehicle(account, billedClient, input.vehicleId(), registration);
        if (vehicle == null) {
            requireStorableRegistration(registration, mode);
        }
        String driverName = trimmedOrNull(input.driverName());
        String driverPhone = trimmedOrNull(input.driverPhone());
        if (vehicle != null && driverName == null) {
            driverName = vehicle.getDefaultDriverName();
            driverPhone = driverPhone != null ? driverPhone : vehicle.getDefaultDriverPhone();
        }
        String recordedRegistration = vehicle != null ? vehicle.getRegistration() : registration;

        VehicleFlag flag = mode == VehicleCheckMode.OFF ? null
                : flagOf(account, mode, vehicle, recordedRegistration, fuelType, amount, litres, saleDate, pending);
        if (vehicle != null) {
            pending.add(vehicle.getId(), litres != null ? litres : BigDecimal.ZERO, amount);
        }
        if (flag != null) {
            notifyFlagged(account.holder(), recordedRegistration, flag);
        }
        return new VehicleCreditDecision(vehicle != null ? vehicle.getId() : null, recordedRegistration,
                driverName, driverPhone, flag);
    }

    private ClientVehicle resolveVehicle(FleetAccountService.Account account, CreditClient billedClient,
                                         Long vehicleId, String registration) {
        List<Long> ownerIds = account.vehicleOwnerIdsFor(billedClient);
        if (vehicleId != null) {
            ClientVehicle vehicle = vehicleRepository.findByIdAndPumpId(vehicleId, billedClient.getPumpId())
                    .filter(found -> ownerIds.contains(found.getClientId()))
                    .orElseThrow(() -> new BusinessException(ErrorCodes.VEHICLE_NOT_ON_ACCOUNT,
                            Map.of("clientName", billedClient.getName()),
                            "That vehicle is not one of '" + billedClient.getName() + "''s vehicles. Pick it again."));
            if (!vehicle.isActive()) {
                throw new BusinessException(ErrorCodes.VEHICLE_INACTIVE,
                        Map.of("registration", vehicle.getRegistration()),
                        "Vehicle " + vehicle.getRegistration() + " is deactivated and cannot take credit.");
            }
            return vehicle;
        }
        if (registration == null) {
            return null;
        }
        return vehicleRepository.findByClientIdInOrderByRegistrationAsc(ownerIds).stream()
                .filter(ClientVehicle::isActive)
                .filter(candidate -> candidate.getRegistration().equals(registration))
                .findFirst()
                .orElse(null);
    }

    private static void requireStorableRegistration(String registration, VehicleCheckMode mode) {
        if (registration == null) {
            return;
        }
        boolean storable = mode == VehicleCheckMode.OFF
                ? registration.length() <= MAX_REGISTRATION_LENGTH
                : VehicleRegistrationNumber.isValid(registration);
        if (!storable) {
            throw new BusinessException(ErrorCodes.VEHICLE_REGISTRATION_INVALID, Map.of("registration", registration),
                    "'" + registration + "' is not a vehicle registration number. Use letters and digits, e.g. MH12AB1234.");
        }
    }

    private VehicleFlag flagOf(FleetAccountService.Account account, VehicleCheckMode mode, ClientVehicle vehicle,
                               String registration, String fuelType, BigDecimal amount, BigDecimal litres,
                               LocalDate saleDate, PendingVehicleUsage pending) {
        String clientName = account.holder().getName();
        if (vehicle == null) {
            if (mode == VehicleCheckMode.BLOCK) {
                throw registration == null
                        ? new BusinessException(ErrorCodes.VEHICLE_REQUIRED_FOR_CREDIT, Map.of("clientName", clientName),
                                "'" + clientName + "' gives credit only to its listed vehicles. Pick the vehicle.")
                        : new BusinessException(ErrorCodes.VEHICLE_NOT_APPROVED,
                                Map.of("clientName", clientName, "registration", registration),
                                "Vehicle " + registration + " is not on '" + clientName + "''s approved list, so it "
                                        + "cannot take credit. Ask a manager to add it on the client's Vehicles tab.");
            }
            return VehicleFlag.UNKNOWN_VEHICLE;
        }
        if (!vehicle.allowsFuel(fuelType)) {
            if (mode == VehicleCheckMode.BLOCK) {
                throw new BusinessException(ErrorCodes.VEHICLE_FUEL_NOT_ALLOWED,
                        Map.of("registration", registration, "fuelType", fuelType),
                        "Vehicle " + registration + " may not take " + fuelType + " on credit.");
            }
            return VehicleFlag.FUEL_NOT_ALLOWED;
        }
        if (exceedsMonthlyLimit(account, vehicle, amount, litres, saleDate, pending, mode)) {
            return VehicleFlag.OVER_MONTHLY_LIMIT;
        }
        return null;
    }

    /** True for a WARN account past a limit; a BLOCK account past one is refused here. */
    private boolean exceedsMonthlyLimit(FleetAccountService.Account account, ClientVehicle vehicle, BigDecimal amount,
                                        BigDecimal litres, LocalDate saleDate, PendingVehicleUsage pending,
                                        VehicleCheckMode mode) {
        if (vehicle.getMonthlyLitreLimit() == null && vehicle.getMonthlyAmountLimit() == null) {
            return false;
        }
        VehicleUsageGroup used = usageService.monthUsageByVehicleId(account.clients(), YearMonth.from(saleDate))
                .get(vehicle.getId());
        BigDecimal usedLitres = (used != null ? used.litres() : BigDecimal.ZERO).add(pending.litresOf(vehicle.getId()));
        BigDecimal usedAmount = (used != null ? used.amount() : BigDecimal.ZERO).add(pending.amountOf(vehicle.getId()));
        BigDecimal thisLitres = Objects.requireNonNullElse(litres, BigDecimal.ZERO);

        if (vehicle.getMonthlyLitreLimit() != null && thisLitres.signum() > 0
                && usedLitres.add(thisLitres).compareTo(vehicle.getMonthlyLitreLimit()) > 0) {
            if (mode == VehicleCheckMode.BLOCK) {
                throw limitExceeded(ErrorCodes.VEHICLE_MONTHLY_LITRE_LIMIT_EXCEEDED, vehicle, usedLitres, thisLitres,
                        vehicle.getMonthlyLitreLimit(), " L");
            }
            return true;
        }
        if (vehicle.getMonthlyAmountLimit() != null
                && usedAmount.add(amount).compareTo(vehicle.getMonthlyAmountLimit()) > 0) {
            if (mode == VehicleCheckMode.BLOCK) {
                throw limitExceeded(ErrorCodes.VEHICLE_MONTHLY_AMOUNT_LIMIT_EXCEEDED, vehicle, usedAmount, amount,
                        vehicle.getMonthlyAmountLimit(), " rupees");
            }
            return true;
        }
        return false;
    }

    private static BusinessException limitExceeded(String code, ClientVehicle vehicle, BigDecimal used,
                                                   BigDecimal requested, BigDecimal limit, String unit) {
        BigDecimal left = limit.subtract(used).max(BigDecimal.ZERO);
        return new BusinessException(code,
                Map.of("registration", vehicle.getRegistration(), "used", money(used), "requested", money(requested),
                        "limit", money(limit), "left", money(left)),
                "Vehicle " + vehicle.getRegistration() + " has used " + money(used) + unit + " of its monthly limit of "
                        + money(limit) + unit + "; this entry of " + money(requested) + unit + " is more than the "
                        + money(left) + unit + " left.");
    }

    private void notifyFlagged(CreditClient holder, String registration, VehicleFlag flag) {
        LocalDate today = businessClock.today();
        String shownRegistration = registration != null ? registration : "-";
        notificationDedupService.maybeInsert(holder.getPumpId(), NotificationType.VEHICLE_CREDIT_FLAGGED,
                "VEHICLE_CREDIT_FLAGGED:client:" + holder.getId() + ":" + today,
                "Credit Vehicle Flagged",
                "Credit for " + holder.getName() + " was given today to a vehicle outside its rules ("
                        + shownRegistration + "). Check the client's Vehicles tab.",
                Map.of("clientName", holder.getName(), "registration", shownRegistration, "flag", flag.name(),
                        "clientId", holder.getId()));
        log.info("Credit vehicle flagged: pump={} client={} registration={} flag={}",
                holder.getPumpId(), holder.getId(), shownRegistration, flag);
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
