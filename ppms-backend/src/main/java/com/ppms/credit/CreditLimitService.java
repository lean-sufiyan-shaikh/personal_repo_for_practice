package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Credit limits of client accounts and the pump's limit settings. A manager may lower a limit or raise it up to the
 * pump's manager cap; a higher limit or unlimited credit needs an Owner or Admin. Every change is kept with its reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditLimitService {

    private final CreditClientAccessService clientAccessService;
    private final CreditClientRepository clientRepository;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditLimitChangeRepository limitChangeRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional
    public CreditClient changeLimit(Long pumpId, Long clientId, UpdateCreditLimitRequest request, User currentUser) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        if (client.getParentClientId() != null) {
            String parentName = clientRepository.findById(client.getParentClientId())
                    .map(CreditClient::getName)
                    .orElse("the main account");
            throw new BusinessException(ErrorCodes.CREDIT_LIMIT_ON_SUB_ACCOUNT,
                    Map.of("clientName", client.getName(), "parentName", parentName),
                    "'" + client.getName() + "' is a sub-account. Its credit limit is set on the main account '"
                            + parentName + "'.");
        }
        CreditClient holder = accountPolicyService.lockLimitHolder(client);
        BigDecimal oldLimit = holder.getCreditLimit().setScale(2, RoundingMode.HALF_UP);
        boolean oldUnlimited = holder.isUnlimitedCredit();
        BigDecimal newLimit = request.getCreditLimit().setScale(2, RoundingMode.HALF_UP);
        boolean newUnlimited = request.isUnlimited();
        if (oldUnlimited == newUnlimited && oldLimit.compareTo(newLimit) == 0) {
            return holder;
        }
        if (!isOwnerOrAdmin(currentUser)) {
            requireWithinManagerRights(requirePump(pumpId), oldLimit, oldUnlimited, newLimit, newUnlimited);
        }

        holder.setCreditLimit(newLimit);
        holder.setUnlimitedCredit(newUnlimited);
        CreditClient saved = clientRepository.save(holder);
        String reason = request.getReason().trim();
        limitChangeRepository.save(CreditLimitChange.builder()
                .pumpId(pumpId)
                .clientId(clientId)
                .oldLimit(oldLimit)
                .newLimit(newLimit)
                .oldUnlimited(oldUnlimited)
                .newUnlimited(newUnlimited)
                .reason(reason)
                .changedByUserId(currentUser.getId())
                .build());

        auditService.log(pumpId, AuditAction.CREDIT_LIMIT_CHANGED, "CreditClient", clientId.toString(),
                "Credit limit for " + saved.getName() + " changed from " + describe(oldLimit, oldUnlimited) + " to "
                        + describe(newLimit, newUnlimited) + ", reason=" + reason,
                currentUser);
        log.info("Credit limit changed: pump={}, client={}, limit {}→{}, unlimited {}→{}, by={}",
                pumpId, clientId, oldLimit, newLimit, oldUnlimited, newUnlimited, currentUser.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CreditLimitChangeResponse> limitChanges(Long pumpId, Long clientId) {
        clientAccessService.requireClientForPump(pumpId, clientId);
        List<CreditLimitChange> changes = limitChangeRepository.findByClientIdOrderByChangedAtDescIdDesc(clientId);
        Set<Long> userIds = changes.stream().map(CreditLimitChange::getChangedByUserId).collect(Collectors.toSet());
        Map<Long, String> nameById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return changes.stream()
                .map(change -> CreditLimitChangeResponse.of(change, nameById.get(change.getChangedByUserId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CreditLimitSettingsResponse settingsOf(Long pumpId) {
        return CreditLimitSettingsResponse.of(requirePump(pumpId));
    }

    @Transactional
    public CreditLimitSettingsResponse updateSettings(Long pumpId, CreditLimitSettingsRequest request, User currentUser) {
        PumpLocation pump = requirePump(pumpId);
        pump.setDefaultCreditLimit(request.defaultCreditLimit().setScale(2, RoundingMode.HALF_UP));
        pump.setManagerCreditLimitCap(request.managerLimitCap().setScale(2, RoundingMode.HALF_UP));
        pump.setNearLimitPercent(request.nearLimitPercent());
        pumpLocationRepository.save(pump);

        String description = "Credit limit settings: default limit ₹" + pump.getDefaultCreditLimit()
                + ", manager cap ₹" + pump.getManagerCreditLimitCap() + ", near limit at " + pump.getNearLimitPercent() + "%";
        auditService.log(pumpId, AuditAction.COLLECTION_SETTINGS_UPDATED, "PumpLocation", pumpId.toString(),
                description, currentUser);
        log.info("Credit limit settings of pump={} updated by user={}: {}", pumpId, currentUser.getId(), description);
        return CreditLimitSettingsResponse.of(pump);
    }

    /** A manager may lower a limit, including from unlimited, or raise it up to the pump's cap. */
    private static void requireWithinManagerRights(PumpLocation pump, BigDecimal oldLimit, boolean oldUnlimited,
                                                   BigDecimal newLimit, boolean newUnlimited) {
        if (newUnlimited && !oldUnlimited) {
            throw new BusinessException(ErrorCodes.CREDIT_LIMIT_UNLIMITED_OWNER_ONLY, Map.of(),
                    "Only an Owner or Admin can give a client unlimited credit.");
        }
        boolean raising = !newUnlimited && !oldUnlimited && newLimit.compareTo(oldLimit) > 0;
        BigDecimal cap = pump.getManagerCreditLimitCap().setScale(2, RoundingMode.HALF_UP);
        if (raising && newLimit.compareTo(cap) > 0) {
            throw new BusinessException(ErrorCodes.CREDIT_LIMIT_ABOVE_MANAGER_CAP,
                    Map.of("cap", cap.toPlainString()),
                    "Managers can set a credit limit up to ₹" + cap.toPlainString()
                            + ". Ask an Owner or Admin for a higher limit.");
        }
    }

    private static boolean isOwnerOrAdmin(User user) {
        return user.getRole() == UserRole.OWNER || user.getRole() == UserRole.ADMIN;
    }

    private static String describe(BigDecimal limit, boolean unlimited) {
        return unlimited ? "unlimited" : "₹" + limit.toPlainString();
    }

    private PumpLocation requirePump(Long pumpId) {
        return pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
    }
}
