package com.ppms.credit;

import com.ppms.auth.ClientIpResolver;
import com.ppms.collection.UpiPaymentLink;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.fleet.VehicleUsageService;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public credit balance lookup, whitelisted in SecurityConfig at /api/public/**.
 * A client proves who they are with their phone number and the portal PIN the pump owner set for them.
 * Every failure gets the same answer so the page cannot be used to find out which phones are clients, and wrong
 * PINs lock a phone out whether or not it is a client's.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class ClientPortalController {

    private static final String NOT_FOUND_MESSAGE = "No credit account matches these details.";

    private final CreditClientRepository creditClientRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final CreditInterestService creditInterestService;
    private final CreditBalanceLookupLimiter lookupLimiter;
    private final CreditPortalAttemptService attemptService;
    private final CreditOverdueService creditOverdueService;
    private final ClientIpResolver clientIpResolver;
    private final PasswordEncoder passwordEncoder;
    private final VehicleUsageService vehicleUsageService;
    private final BusinessClock businessClock;
    private final CreditPortalSessionService sessionService;
    // Compared against when no client matches, so a wrong phone takes as long as a wrong PIN.
    private volatile String unusedPinHash;

    @PostMapping("/credit-balance")
    public CreditBalanceLookupResponse getBalance(@Valid @RequestBody CreditBalanceLookupRequest lookup,
                                                  HttpServletRequest request) {
        lookupLimiter.checkLookupAllowed(clientIpResolver.resolve(request));
        attemptService.requireNotLocked(lookup.pumpId(), lookup.phone());

        List<CreditClient> candidates = clientsWithPin(lookup);
        Optional<CreditClient> matched = matchingPin(candidates, lookup.pin());
        if (matched.isEmpty()) {
            if (pumpLocationRepository.existsById(lookup.pumpId())) {
                attemptService.recordFailure(lookup.pumpId(), lookup.phone(),
                        candidates.isEmpty() ? null : candidates.get(0).getId());
            }
            throw new ResourceNotFoundException(ErrorCodes.CREDIT_ACCOUNT_NOT_MATCHED, Map.of(), NOT_FOUND_MESSAGE);
        }
        attemptService.clearAttempts(lookup.pumpId(), lookup.phone());
        CreditClient client = matched.get();
        if (client.getPortalPinWeak() == null) {
            client.setPortalPinWeak(PortalPinRules.isWeak(lookup.pin()));
            creditClientRepository.save(client);
        }
        String sessionToken = sessionService.open(lookup.pumpId(), client.getId());
        return client.getParentClientId() != null ? subAccountBalance(client, sessionToken)
                : groupBalance(lookup.pumpId(), client, sessionToken);
    }

    /** The same group balance the statement and reminders ask the client to pay. */
    private CreditBalanceLookupResponse groupBalance(Long pumpId, CreditClient client, String sessionToken) {
        BigDecimal balance = creditOverdueService.standingOfAccount(client, businessClock.today()).outstanding();
        BigDecimal outstanding = balance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        String upiPayLink = pumpLocationRepository.findById(pumpId)
                .map(pump -> UpiPaymentLink.forPump(pump, outstanding, client.getName()))
                .orElse(null);
        return new CreditBalanceLookupResponse(outstanding,
                client.isUnlimitedCredit() ? null : client.getCreditLimit(), client.isUnlimitedCredit(), false,
                upiPayLink, vehicleUsageOf(client), CreditLedgerQueryService.advanceOf(balance), sessionToken);
    }

    /** A sub-account sees what it used; the balance it owes is held, and paid, on its main account. */
    private CreditBalanceLookupResponse subAccountBalance(CreditClient client, String sessionToken) {
        BigDecimal ownUsage = creditInterestService.computeOutstanding(client.getId());
        return new CreditBalanceLookupResponse(ownUsage, null, false, true, null, vehicleUsageOf(client),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), sessionToken);
    }

    /** A parent sees the vehicles of its whole group; a standalone client or a sub-account sees its own credit. */
    private List<CreditBalanceLookupResponse.VehicleMonth> vehicleUsageOf(CreditClient client) {
        List<CreditClient> accounts = new ArrayList<>();
        accounts.add(client);
        if (client.getParentClientId() == null) {
            accounts.addAll(creditClientRepository.findByParentClientId(client.getId()));
        }
        YearMonth thisMonth = YearMonth.from(businessClock.today());
        return List.of(monthOf(accounts, thisMonth), monthOf(accounts, thisMonth.minusMonths(1)));
    }

    private CreditBalanceLookupResponse.VehicleMonth monthOf(List<CreditClient> accounts, YearMonth month) {
        return new CreditBalanceLookupResponse.VehicleMonth(month,
                vehicleUsageService.usageByVehicle(accounts, month.atDay(1), month.atEndOfMonth()).stream()
                        .map(group -> new CreditBalanceLookupResponse.VehicleTotal(group.registration(), group.litres(),
                                group.amount(), group.lines().size()))
                        .toList());
    }

    private List<CreditClient> clientsWithPin(CreditBalanceLookupRequest lookup) {
        boolean pumpOpen = pumpLocationRepository.findById(lookup.pumpId()).map(PumpLocation::isEnabled).orElse(false);
        return pumpOpen
                ? creditClientRepository.findAllByPumpIdAndPhoneAndIsActiveTrue(lookup.pumpId(), lookup.phone()).stream()
                        .filter(client -> client.getPortalPinHash() != null)
                        .toList()
                : List.of();
    }

    private Optional<CreditClient> matchingPin(List<CreditClient> candidates, String pin) {
        if (candidates.isEmpty()) {
            passwordEncoder.matches(pin, unusedPinHash());
            return Optional.empty();
        }
        return candidates.stream()
                .filter(client -> passwordEncoder.matches(pin, client.getPortalPinHash()))
                .findFirst();
    }

    private String unusedPinHash() {
        if (unusedPinHash == null) {
            unusedPinHash = passwordEncoder.encode("000000");
        }
        return unusedPinHash;
    }
}
