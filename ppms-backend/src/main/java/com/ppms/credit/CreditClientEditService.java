package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.invoice.GstFields;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creating, editing and enabling credit clients, each audited with what changed. A new main account starts with the
 * pump's default credit limit. Changing a phone or GSTIN, or enabling a disabled client, needs an Owner or Admin; a
 * new phone turns off the balance-page PIN, since it was given to whoever held the old number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditClientEditService {

    private final CreditClientRepository clientRepository;
    private final CreditClientAccessService clientAccessService;
    private final PumpLocationRepository pumpLocationRepository;
    private final NotificationDedupService notificationDedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public CreditClient createClient(Long pumpId, CreateCreditClientRequest request, User currentUser) {
        String name = request.getName().trim();
        Long parentClientId = request.getParentClientId();
        if (parentClientId == null) {
            requireRootNameFree(pumpId, name);
        } else {
            CreditClient parent = clientAccessService.requireClientForPump(pumpId, parentClientId);
            if (parent.getParentClientId() != null) {
                throw new BusinessException(ErrorCodes.CREDIT_SUB_ACCOUNT_NESTING_NOT_ALLOWED, Map.of(),
                        "Cannot create a sub-account under another sub-account. Only one level of nesting is allowed.");
            }
            requireSubAccountNameFree(parentClientId, name);
        }

        String phone = GstFields.trimmedOrNull(request.getPhone());
        if (phone != null) {
            boolean conflict = parentClientId == null
                    ? clientRepository.existsByPumpIdAndPhone(pumpId, phone)
                    : clientRepository.existsPhoneConflictForSubAccount(pumpId, phone, parentClientId);
            requireNoPhoneConflict(conflict);
        }

        BigDecimal startingLimit = parentClientId == null ? defaultCreditLimitOf(pumpId) : BigDecimal.ZERO;
        CreditClient client = clientRepository.save(CreditClient.builder()
                .pumpId(pumpId)
                .name(name)
                .phone(phone)
                .notes(request.getNotes())
                .creditLimit(startingLimit)
                .parentClientId(parentClientId)
                .gstin(GstFields.normaliseGstin(request.getGstin()))
                .billingAddress(GstFields.trimmedOrNull(request.getBillingAddress()))
                .build());

        auditService.log(pumpId, AuditAction.CREDIT_CLIENT_CREATED, "CreditClient", client.getId().toString(),
                "Credit client " + client.getName() + " created"
                        + (parentClientId != null ? " under parentClientId=" + parentClientId : "")
                        + ", phone " + maskedPhone(client.getPhone()) + ", GSTIN " + orNone(client.getGstin())
                        + (parentClientId == null ? ", credit limit ₹" + startingLimit.toPlainString() + ", unlimited=false" : ""),
                currentUser);
        return client;
    }

    @Transactional
    public CreditClient updateClient(Long pumpId, Long clientId, UpdateCreditClientRequest request, User currentUser) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        List<String> changes = new ArrayList<>();

        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equalsIgnoreCase(client.getName())) {
                if (client.getParentClientId() == null) {
                    requireRootNameFree(pumpId, name);
                } else {
                    requireSubAccountNameFree(client.getParentClientId(), name);
                }
            }
            if (!name.equals(client.getName())) {
                changes.add("name " + client.getName() + " → " + name);
                client.setName(name);
            }
        }
        boolean phoneChanged = false;
        if (request.phone() != null) {
            String phone = GstFields.trimmedOrNull(request.phone());
            if (!Objects.equals(phone, client.getPhone())) {
                requireOwnerOrAdmin(currentUser, ErrorCodes.CREDIT_CLIENT_PHONE_CHANGE_OWNER_ONLY,
                        "Only an Owner or Admin can change a client's phone number.");
                if (phone != null) {
                    requireNoPhoneConflict(client.getParentClientId() == null
                            ? clientRepository.existsPhoneConflictForRootClientUpdate(pumpId, phone, clientId)
                            : clientRepository.existsPhoneConflictForSubAccount(pumpId, phone, client.getParentClientId()));
                }
                changes.add("phone " + maskedPhone(client.getPhone()) + " → " + maskedPhone(phone));
                client.setPhone(phone);
                phoneChanged = true;
            }
        }
        if (request.notes() != null) {
            String notes = GstFields.trimmedOrNull(request.notes());
            if (!Objects.equals(notes, client.getNotes())) {
                changes.add("notes changed");
                client.setNotes(notes);
            }
        }
        if (request.gstin() != null) {
            String gstin = GstFields.normaliseGstin(request.gstin());
            if (!Objects.equals(gstin, client.getGstin())) {
                requireOwnerOrAdmin(currentUser, ErrorCodes.CREDIT_CLIENT_GSTIN_CHANGE_OWNER_ONLY,
                        "Only an Owner or Admin can change a client's GSTIN.");
                changes.add("GSTIN " + orNone(client.getGstin()) + " → " + orNone(gstin));
                client.setGstin(gstin);
            }
        }
        if (request.billingAddress() != null) {
            String address = GstFields.trimmedOrNull(request.billingAddress());
            if (!Objects.equals(address, client.getBillingAddress())) {
                changes.add("billing address changed");
                client.setBillingAddress(address);
            }
        }
        if (changes.isEmpty()) {
            return client;
        }
        if (phoneChanged && client.getPortalPinHash() != null) {
            client.setPortalPinHash(null);
            client.setPortalPinWeak(null);
            changes.add("balance-page PIN turned off");
        }

        CreditClient saved = clientRepository.save(client);
        auditService.log(pumpId, AuditAction.CREDIT_CLIENT_UPDATED, "CreditClient", clientId.toString(),
                "Credit client " + saved.getName() + " updated: " + String.join(", ", changes), currentUser);
        if (phoneChanged) {
            notifyPhoneChanged(pumpId, saved);
        }
        return saved;
    }

    @Transactional
    public CreditClient changeStatus(Long pumpId, Long clientId, boolean active, User currentUser) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        if (client.isActive() == active) {
            return client;
        }
        if (active) {
            requireOwnerOrAdmin(currentUser, ErrorCodes.CREDIT_CLIENT_ENABLE_OWNER_ONLY,
                    "Only an Owner or Admin can enable a disabled client.");
            CreditDeskRules.requireOpenAccount(client);
        }
        client.setActive(active);
        CreditClient saved = clientRepository.save(client);
        auditService.log(pumpId, AuditAction.CREDIT_CLIENT_STATUS_CHANGED, "CreditClient", clientId.toString(),
                "Credit client " + saved.getName() + (active ? " enabled" : " disabled"), currentUser);
        return saved;
    }

    private void notifyPhoneChanged(Long pumpId, CreditClient client) {
        notificationDedupService.maybeInsert(pumpId, NotificationType.CREDIT_CLIENT_PHONE_CHANGED,
                "CREDIT_CLIENT_PHONE_CHANGED:client:" + client.getId() + ":" + businessClock.now().toInstant(),
                "Client Phone Changed",
                "The phone number of " + client.getName() + " was changed. Its balance-page PIN is turned off until "
                        + "you set a new one.",
                Map.of("clientName", client.getName(), "clientId", client.getId()));
        log.info("Credit client phone changed: pump={}, client={}", pumpId, client.getId());
    }

    private void requireRootNameFree(Long pumpId, String name) {
        if (clientRepository.existsByPumpIdAndNameIgnoreCaseAndParentClientIdIsNull(pumpId, name)) {
            throw new BusinessException(ErrorCodes.CREDIT_CLIENT_NAME_TAKEN, Map.of("clientName", name),
                    "A client named \"" + name + "\" already exists.");
        }
    }

    private void requireSubAccountNameFree(Long parentClientId, String name) {
        if (clientRepository.existsByParentClientIdAndNameIgnoreCase(parentClientId, name)) {
            throw new BusinessException(ErrorCodes.CREDIT_SUB_ACCOUNT_NAME_TAKEN, Map.of("clientName", name),
                    "A sub-account named \"" + name + "\" already exists under this parent.");
        }
    }

    private static void requireNoPhoneConflict(boolean conflict) {
        if (conflict) {
            throw new BusinessException(ErrorCodes.CREDIT_CLIENT_PHONE_TAKEN, Map.of(),
                    "This phone number is already registered to another client group in this pump.");
        }
    }

    private static void requireOwnerOrAdmin(User user, String code, String message) {
        if (user.getRole() != UserRole.OWNER && user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(code, Map.of(), message);
        }
    }

    private BigDecimal defaultCreditLimitOf(Long pumpId) {
        return pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId))
                .getDefaultCreditLimit();
    }

    /** Audit lines are also written to the application log, so only the last four digits of a phone are kept. */
    static String maskedPhone(String phone) {
        if (phone == null) {
            return "none";
        }
        return phone.length() <= 4 ? "****" : "******" + phone.substring(phone.length() - 4);
    }

    private static String orNone(String value) {
        return value == null ? "none" : value;
    }
}
