package com.ppms.fleet;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditClientRepository;
import com.ppms.fuel.FuelType;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Lists, adds, edits and deactivates a client's vehicles, and sets the account's vehicle check. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientVehicleService {

    private static final Set<String> FUEL_TYPES = Arrays.stream(FuelType.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final CreditClientAccessService clientAccessService;
    private final CreditClientRepository clientRepository;
    private final FleetAccountService fleetAccountService;
    private final ClientVehicleRepository vehicleRepository;
    private final VehicleUsageService usageService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    /** @param activeOnly true for the credit entry picker, which offers only vehicles that may take credit */
    @Transactional(readOnly = true)
    public ClientVehiclesResponse vehiclesOf(Long pumpId, Long clientId, boolean activeOnly) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        FleetAccountService.Account account = fleetAccountService.accountOf(client);
        Map<Long, String> nameById = account.clients().stream()
                .collect(Collectors.toMap(CreditClient::getId, CreditClient::getName));
        Map<Long, VehicleUsageGroup> usedById = usageService.monthUsageByVehicleId(account.clients(),
                YearMonth.from(businessClock.today()));
        List<ClientVehicleResponse> vehicles = vehicleRepository
                .findByClientIdInOrderByRegistrationAsc(account.vehicleOwnerIdsFor(client)).stream()
                .filter(vehicle -> !activeOnly || vehicle.isActive())
                .map(vehicle -> toResponse(vehicle, nameById.get(vehicle.getClientId()), usedById.get(vehicle.getId())))
                .toList();
        CreditClient holder = account.holder();
        return new ClientVehiclesResponse(holder.getVehicleCheck(), holder.getId(), holder.getName(), vehicles);
    }

    @Transactional
    public ClientVehicleResponse addVehicle(Long pumpId, Long clientId, SaveVehicleRequest request, User currentUser) {
        CreditClient owner = clientAccessService.requireClientForPump(pumpId, clientId);
        String registration = requireValidRegistration(request.registration());
        if (vehicleRepository.existsByPumpIdAndRegistrationAndIsActiveTrue(pumpId, registration)) {
            throw registrationTaken(registration);
        }
        ClientVehicle vehicle = ClientVehicle.builder().pumpId(pumpId).clientId(owner.getId()).build();
        applyDetails(vehicle, registration, request);
        ClientVehicle saved = vehicleRepository.save(vehicle);
        auditService.log(pumpId, AuditAction.CLIENT_VEHICLE_SAVED, "ClientVehicle", saved.getId().toString(),
                "Vehicle " + registration + " added for '" + owner.getName() + "'" + limitsText(saved), currentUser);
        log.info("Client vehicle added: pump={} client={} vehicle={} by={}", pumpId, owner.getId(), saved.getId(),
                currentUser.getId());
        return responseOf(saved);
    }

    @Transactional
    public ClientVehicleResponse updateVehicle(Long pumpId, Long clientId, Long vehicleId, SaveVehicleRequest request,
                                               User currentUser) {
        ClientVehicle vehicle = requireVehicleOfClient(pumpId, clientId, vehicleId);
        String registration = requireValidRegistration(request.registration());
        if (vehicle.isActive() && vehicleRepository.existsByPumpIdAndRegistrationAndIsActiveTrueAndIdNot(
                pumpId, registration, vehicleId)) {
            throw registrationTaken(registration);
        }
        applyDetails(vehicle, registration, request);
        ClientVehicle saved = vehicleRepository.save(vehicle);
        auditService.log(pumpId, AuditAction.CLIENT_VEHICLE_SAVED, "ClientVehicle", vehicleId.toString(),
                "Vehicle " + registration + " updated" + limitsText(saved), currentUser);
        return responseOf(saved);
    }

    /** Reactivating needs the registration to be free among the pump's active vehicles. */
    @Transactional
    public ClientVehicleResponse setVehicleActive(Long pumpId, Long clientId, Long vehicleId, boolean active,
                                                  User currentUser) {
        ClientVehicle vehicle = requireVehicleOfClient(pumpId, clientId, vehicleId);
        if (active && !vehicle.isActive() && vehicleRepository.existsByPumpIdAndRegistrationAndIsActiveTrue(
                pumpId, vehicle.getRegistration())) {
            throw registrationTaken(vehicle.getRegistration());
        }
        vehicle.setActive(active);
        ClientVehicle saved = vehicleRepository.save(vehicle);
        auditService.log(pumpId, AuditAction.CLIENT_VEHICLE_STATUS_CHANGED, "ClientVehicle", vehicleId.toString(),
                "Vehicle " + vehicle.getRegistration() + (active ? " reactivated" : " deactivated"), currentUser);
        return responseOf(saved);
    }

    /** The check is the account's: it is set on the parent (or standalone client) and covers its sub-accounts. */
    @Transactional
    public ClientVehiclesResponse setVehicleCheck(Long pumpId, Long clientId, UpdateVehicleCheckRequest request,
                                                  User currentUser) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        if (client.getParentClientId() != null) {
            throw new BusinessException(ErrorCodes.VEHICLE_CHECK_ON_SUB_ACCOUNT, Map.of("clientName", client.getName()),
                    "The vehicle check of sub-account '" + client.getName() + "' is set on its parent account.");
        }
        var previous = client.getVehicleCheck();
        client.setVehicleCheck(request.vehicleCheck());
        clientRepository.save(client);
        auditService.log(pumpId, AuditAction.VEHICLE_CHECK_CHANGED, "CreditClient", clientId.toString(),
                "Vehicle check for '" + client.getName() + "' changed from " + previous + " to "
                        + request.vehicleCheck(), currentUser);
        return vehiclesOf(pumpId, clientId, false);
    }

    private ClientVehicle requireVehicleOfClient(Long pumpId, Long clientId, Long vehicleId) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        List<Long> ownerIds = fleetAccountService.accountOf(client).vehicleOwnerIdsFor(client);
        return vehicleRepository.findByIdAndPumpId(vehicleId, pumpId)
                .filter(vehicle -> ownerIds.contains(vehicle.getClientId()))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.VEHICLE, "Vehicle not found: " + vehicleId));
    }

    private void applyDetails(ClientVehicle vehicle, String registration, SaveVehicleRequest request) {
        List<String> fuelTypes = request.allowedFuelTypes() == null ? List.of() : request.allowedFuelTypes().stream()
                .distinct().toList();
        fuelTypes.stream().filter(fuelType -> !FUEL_TYPES.contains(fuelType)).findFirst().ifPresent(fuelType -> {
            throw new BusinessException(ErrorCodes.VEHICLE_FUEL_TYPE_INVALID, Map.of("fuelType", fuelType),
                    "'" + fuelType + "' is not a fuel type.");
        });
        vehicle.setRegistration(registration);
        vehicle.setAllowedFuelTypes(fuelTypes.toArray(String[]::new));
        vehicle.setMonthlyLitreLimit(scaled(request.monthlyLitreLimit()));
        vehicle.setMonthlyAmountLimit(scaled(request.monthlyAmountLimit()));
        vehicle.setDefaultDriverName(blankToNull(request.defaultDriverName()));
        vehicle.setDefaultDriverPhone(blankToNull(request.defaultDriverPhone()));
    }

    private static String requireValidRegistration(String input) {
        String registration = VehicleRegistrationNumber.normalise(input);
        if (!VehicleRegistrationNumber.isValid(registration)) {
            String shown = input == null ? "" : input.trim();
            throw new BusinessException(ErrorCodes.VEHICLE_REGISTRATION_INVALID, Map.of("registration", shown),
                    "'" + shown + "' is not a vehicle registration number. Use letters and digits, e.g. MH12AB1234.");
        }
        return registration;
    }

    private static BusinessException registrationTaken(String registration) {
        return new BusinessException(ErrorCodes.VEHICLE_REGISTRATION_TAKEN, Map.of("registration", registration),
                "Vehicle " + registration + " is already listed as an active vehicle at this pump.");
    }

    private ClientVehicleResponse responseOf(ClientVehicle vehicle) {
        CreditClient owner = clientRepository.findById(vehicle.getClientId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + vehicle.getClientId()));
        Map<Long, VehicleUsageGroup> usedById = usageService.monthUsageByVehicleId(
                fleetAccountService.accountOf(owner).clients(), YearMonth.from(businessClock.today()));
        return toResponse(vehicle, owner.getName(), usedById.get(vehicle.getId()));
    }

    private static ClientVehicleResponse toResponse(ClientVehicle vehicle, String ownerName, VehicleUsageGroup usedThisMonth) {
        BigDecimal litres = usedThisMonth != null ? usedThisMonth.litres() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = usedThisMonth != null ? usedThisMonth.amount() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return new ClientVehicleResponse(vehicle.getId(), vehicle.getClientId(), ownerName, vehicle.getRegistration(),
                List.of(vehicle.getAllowedFuelTypes()), vehicle.getMonthlyLitreLimit(), vehicle.getMonthlyAmountLimit(),
                vehicle.getDefaultDriverName(), vehicle.getDefaultDriverPhone(), vehicle.isActive(), litres, amount,
                leftOf(vehicle.getMonthlyLitreLimit(), litres), leftOf(vehicle.getMonthlyAmountLimit(), amount));
    }

    private static BigDecimal leftOf(BigDecimal limit, BigDecimal used) {
        return limit == null ? null : limit.subtract(used).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static String limitsText(ClientVehicle vehicle) {
        return " (fuels " + (vehicle.getAllowedFuelTypes().length == 0 ? "any" : String.join("/", vehicle.getAllowedFuelTypes()))
                + ", monthly limit " + (vehicle.getMonthlyLitreLimit() != null ? vehicle.getMonthlyLitreLimit() + " L" : "-")
                + " / " + (vehicle.getMonthlyAmountLimit() != null ? "₹" + vehicle.getMonthlyAmountLimit() : "-") + ")";
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
