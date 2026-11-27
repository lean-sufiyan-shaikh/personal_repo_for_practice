package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges a duplicate client into the client it duplicates, both at the same pump and either both main accounts or
 * both sub-accounts of one parent. Every record of the duplicate moves to the target in one transaction with both
 * accounts locked; the duplicate's sub-accounts move under the target. Issued invoices and GST credit notes keep their
 * buyer snapshot and stay with the duplicate, which is closed as merged and loses its portal PIN. The target's interest
 * is recalculated from the earliest moved date, so the moved bills and payments are charged as if always on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditClientMergeService {

    /** Tables whose rows belong to a client and move with it, in no particular order. */
    private static final List<String> MOVED_TABLES = List.of("shift_credit_entries", "ancillary_sales",
            "credit_payments", "credit_payment_reversals", "credit_interest_charges", "credit_adjustments",
            "credit_disputes", "credit_deposits", "credit_opening_balances", "credit_payment_promises",
            "credit_reminders", "client_vehicles");

    private final CreditClientAccessService clientAccessService;
    private final CreditClientRepository clientRepository;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditInterestService interestService;
    private final EntityManager entityManager;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public CreditClient merge(Long pumpId, Long sourceId, Long targetId, User mergedBy) {
        if (sourceId.equals(targetId)) {
            throw new BusinessException(ErrorCodes.CREDIT_MERGE_SAME_CLIENT, Map.of(), "Pick two different clients to merge.");
        }
        CreditClient source = clientAccessService.requireClientForPump(pumpId, sourceId);
        CreditClient target = clientAccessService.requireClientForPump(pumpId, targetId);
        accountPolicyService.lockLimitHolders(Set.of(accountPolicyService.limitHolderId(source),
                accountPolicyService.limitHolderId(target)));
        requireCompatible(source, target);
        entityManager.flush();

        String reason = "Merged into '" + target.getName() + "' (#" + targetId + ")";
        LocalDate earliestMovedDate = earliestRecordDate(sourceId);
        Map<String, Integer> movedRows = moveRecords(sourceId, targetId, reason, mergedBy);
        int movedSubAccounts = entityManager.createNativeQuery(
                        "UPDATE credit_clients SET parent_client_id = :target WHERE parent_client_id = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
        entityManager.clear();

        CreditClient merged = clientRepository.findById(sourceId).orElseThrow();
        merged.setClosedAt(businessClock.now());
        merged.setClosedBy(mergedBy.getId());
        merged.setCloseReason(reason);
        merged.setMergedIntoClientId(targetId);
        merged.setActive(false);
        merged.setPortalPinHash(null);
        merged.setPortalPinWeak(null);
        clientRepository.saveAndFlush(merged);
        if (earliestMovedDate != null) {
            interestService.recalculateChargedInterestFrom(targetId, earliestMovedDate,
                    "Recalculated after merging '" + source.getName() + "' into this account", mergedBy);
        }

        auditService.log(pumpId, AuditAction.CREDIT_CLIENTS_MERGED, "CreditClient", sourceId.toString(),
                "Client '" + source.getName() + "' (#" + sourceId + ") merged into '" + target.getName() + "' (#"
                        + targetId + "): moved " + movedRows + ", sub-accounts=" + movedSubAccounts, mergedBy);
        log.info("Credit clients merged: pump={} source={} target={} moved={} subAccounts={} by={}", pumpId, sourceId,
                targetId, movedRows, movedSubAccounts, mergedBy.getId());
        return clientRepository.findById(targetId).orElseThrow();
    }

    private void requireCompatible(CreditClient source, CreditClient target) {
        boolean bothMain = source.getParentClientId() == null && target.getParentClientId() == null;
        boolean subsOfOneParent = source.getParentClientId() != null
                && source.getParentClientId().equals(target.getParentClientId());
        if (!bothMain && !subsOfOneParent) {
            throw new BusinessException(ErrorCodes.CREDIT_MERGE_INCOMPATIBLE,
                    Map.of("sourceName", source.getName(), "targetName", target.getName()),
                    "'" + source.getName() + "' and '" + target.getName() + "' can be merged only when both are main "
                            + "accounts or both are sub-accounts of the same parent.");
        }
        CreditDeskRules.requireOpenAccount(source);
        CreditDeskRules.requireOpenAccount(target);
        boolean clashingSubAccount = clientRepository.findByParentClientId(source.getId()).stream()
                .anyMatch(child -> clientRepository.existsByParentClientIdAndNameIgnoreCase(target.getId(), child.getName()));
        if (clashingSubAccount) {
            throw new BusinessException(ErrorCodes.CREDIT_MERGE_SUB_ACCOUNT_NAME_TAKEN,
                    Map.of("targetName", target.getName()),
                    "A sub-account of '" + source.getName() + "' has the same name as one of '" + target.getName()
                            + "'. Rename it first.");
        }
    }

    private LocalDate earliestRecordDate(Long sourceId) {
        Object earliest = entityManager.createNativeQuery("""
                SELECT MIN(d) FROM (
                    SELECT MIN(sale_date) AS d FROM shift_credit_entries WHERE client_id = :id
                    UNION ALL SELECT MIN(sale_date) FROM ancillary_sales WHERE client_id = :id
                    UNION ALL SELECT MIN(payment_date) FROM credit_payments WHERE client_id = :id
                    UNION ALL SELECT MIN(as_of_date) FROM credit_opening_balances WHERE client_id = :id
                    UNION ALL SELECT MIN(LEAST(adjustment_date, COALESCE(debit_date, adjustment_date)))
                              FROM credit_adjustments WHERE client_id = :id
                    UNION ALL SELECT MIN(period_from) FROM credit_interest_charges WHERE client_id = :id
                ) dates
                """).setParameter("id", sourceId).getSingleResult();
        if (earliest instanceof LocalDate date) {
            return date;
        }
        return earliest == null ? null : ((Date) earliest).toLocalDate();
    }

    /**
     * A live charge of the duplicate for the same period as a live charge of the target is waived first, as only one
     * live charge may cover a period; the recalculation then charges that period on the merged balance. Likewise an
     * open promise of the duplicate is cancelled when the target already has one.
     */
    private Map<String, Integer> moveRecords(Long sourceId, Long targetId, String reason, User mergedBy) {
        entityManager.createNativeQuery("""
                UPDATE credit_interest_charges s
                SET voided_at = NOW(), voided_by = :actor, void_reason = :reason, waiver_kind = 'RECALCULATE'
                WHERE s.client_id = :source AND s.voided_at IS NULL AND EXISTS (
                    SELECT 1 FROM credit_interest_charges t WHERE t.client_id = :target AND t.voided_at IS NULL
                      AND t.period_from = s.period_from AND t.period_to = s.period_to)
                """).setParameter("actor", mergedBy.getId()).setParameter("reason", reason)
                .setParameter("source", sourceId).setParameter("target", targetId).executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO credit_payment_promise_changes
                    (promise_id, change_type, previous_amount, previous_promised_date, reason, changed_by_user_id)
                SELECT id, 'CANCELLED', amount, promised_date, :reason, :actor FROM credit_payment_promises
                WHERE client_id = :source AND status = 'OPEN'
                  AND EXISTS (SELECT 1 FROM credit_payment_promises WHERE client_id = :target AND status = 'OPEN')
                """).setParameter("actor", mergedBy.getId()).setParameter("reason", reason)
                .setParameter("source", sourceId).setParameter("target", targetId).executeUpdate();
        entityManager.createNativeQuery("""
                UPDATE credit_payment_promises SET status = 'CANCELLED', resolved_at = NOW()
                WHERE client_id = :source AND status = 'OPEN'
                  AND EXISTS (SELECT 1 FROM credit_payment_promises WHERE client_id = :target AND status = 'OPEN')
                """).setParameter("source", sourceId).setParameter("target", targetId).executeUpdate();

        Map<String, Integer> movedRows = new LinkedHashMap<>();
        for (String table : MOVED_TABLES) {
            int moved = entityManager.createNativeQuery("UPDATE " + table + " SET client_id = :target WHERE client_id = :source")
                    .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
            if (moved > 0) {
                movedRows.put(table, moved);
            }
        }
        return movedRows;
    }
}
