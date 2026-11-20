package com.ppms.expense;

import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Decides whether an expense or credit payment needs Owner/Admin approval, using the pump's
 * expense_approval_threshold. No threshold configured means no approval is needed.
 */
@Component
@RequiredArgsConstructor
public class ApprovalPolicy {

    private final PumpLocationRepository pumpLocationRepository;

    public ExpenseApprovalStatus resolveStatus(Long pumpId, User submittedBy, BigDecimal amount) {
        if (canApprove(submittedBy)) {
            return ExpenseApprovalStatus.APPROVED;
        }
        BigDecimal threshold = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Pump not found: " + pumpId))
                .getExpenseApprovalThreshold();
        if (threshold == null || amount.compareTo(threshold) <= 0) {
            return ExpenseApprovalStatus.APPROVED;
        }
        return ExpenseApprovalStatus.PENDING_APPROVAL;
    }

    private static boolean canApprove(User user) {
        return user.getRole() == UserRole.OWNER
                || user.getRole() == UserRole.ADMIN
                || user.getRole() == UserRole.SUPER_ADMIN;
    }
}
