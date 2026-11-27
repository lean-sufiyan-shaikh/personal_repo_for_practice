package com.ppms.credit;

import java.util.Map;
import com.ppms.ancillary.AncillarySaleRepository;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.shift.ShiftCreditEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditClientService {

    private final CreditClientRepository clientRepository;
    private final CreditClientAccessService clientAccessService;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final CreditExtensionRepository extensionRepository;
    private final CreditEntryReassignmentRepository reassignmentRepository;
    private final AncillarySaleRepository ancillarySaleRepository;
    private final CreditOpeningBalanceRepository openingBalanceRepository;
    private final PasswordEncoder passwordEncoder;
    private final CreditPortalAttemptService portalAttemptService;

    /** Only a client with no ledger history can be removed; anyone else is deactivated so the history stays intact. */
    @Transactional
    public void deleteClient(Long pumpId, Long clientId) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        if (!clientRepository.findByParentClientId(clientId).isEmpty()) {
            throw new BusinessException(ErrorCodes.CREDIT_CLIENT_HAS_SUB_ACCOUNTS, Map.of(),
                    "Cannot remove a parent account that has sub-accounts. Please remove all sub-accounts first.");
        }
        if (hasLedgerHistory(clientId)) {
            throw new BusinessException(ErrorCodes.CREDIT_CLIENT_HAS_LEDGER_HISTORY,
                    Map.of(),
                    "Client has ledger history — deactivate instead.");
        }
        clientRepository.delete(client);
        log.info("Credit client deleted: pump={}, client={}", pumpId, clientId);
    }

    /** A new PIN must not be easy to guess; setting one also lifts any lockout of the client's phone. */
    @Transactional
    public void setPortalPin(Long pumpId, Long clientId, String pin) {
        if (PortalPinRules.isTrivial(pin)) {
            throw new BusinessException(ErrorCodes.PORTAL_PIN_TOO_EASY, Map.of(),
                    "Choose a PIN that is hard to guess: not one digit repeated, not a run like 123456, "
                            + "and not a common PIN.");
        }
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        client.setPortalPinHash(passwordEncoder.encode(pin));
        client.setPortalPinWeak(PortalPinRules.isWeak(pin));
        clientRepository.save(client);
        if (client.getPhone() != null) {
            portalAttemptService.clearAttempts(pumpId, client.getPhone());
        }
        log.info("Credit portal PIN set: pump={}, client={}", pumpId, clientId);
    }

    @Transactional
    public void removePortalPin(Long pumpId, Long clientId) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        client.setPortalPinHash(null);
        client.setPortalPinWeak(null);
        clientRepository.save(client);
        log.info("Credit portal PIN removed: pump={}, client={}", pumpId, clientId);
    }

    private boolean hasLedgerHistory(Long clientId) {
        return creditEntryRepository.existsByClientId(clientId)
                || paymentRepository.existsByClientId(clientId)
                || interestChargeRepository.existsByClientId(clientId)
                || extensionRepository.existsByClientId(clientId)
                || reassignmentRepository.existsByFromClientIdOrToClientId(clientId, clientId)
                || ancillarySaleRepository.existsByClientId(clientId)
                || openingBalanceRepository.existsByClientId(clientId);
    }
}
