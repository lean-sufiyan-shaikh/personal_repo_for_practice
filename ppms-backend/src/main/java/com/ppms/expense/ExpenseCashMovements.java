package com.ppms.expense;

import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.pettycash.PettyCashLedger;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves the cash of an approved expense: out of the safe or out of the petty cash box. Bank and owner's-pocket
 * expenses move no cash, and neither do expenses recorded before the source was asked for.
 */
@Component
@RequiredArgsConstructor
class ExpenseCashMovements {

    private static final int DESCRIPTION_LIMIT = 200;

    private final CashDrawerService cashDrawerService;
    private final PettyCashLedger pettyCashLedger;

    @Transactional(propagation = Propagation.MANDATORY)
    void payOut(PumpExpense expense, User currentUser) {
        if (expense.getPaidFrom() == ExpensePaidFrom.SAFE) {
            CashEvent cashOut = cashDrawerService.recordAutomaticCashOut(expense.getPumpId(), expense.getAmount(),
                    expense.getExpenseDate(), describe(expense), currentUser);
            expense.setCashEventId(cashOut.getId());
        } else if (expense.getPaidFrom() == ExpensePaidFrom.PETTY_CASH) {
            pettyCashLedger.drawForExpense(expense.getPumpId(), expense.getId(), expense.getAmount(),
                    expense.getExpenseDate(), describe(expense), currentUser);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void reverse(PumpExpense expense, String reason, User currentUser) {
        if (expense.getPaidFrom() == ExpensePaidFrom.SAFE && expense.getCashEventId() != null) {
            cashDrawerService.reverseAutomaticEvent(expense.getPumpId(), expense.getCashEventId(),
                    "expense #" + expense.getId() + " voided: " + reason, currentUser);
        } else if (expense.getPaidFrom() == ExpensePaidFrom.PETTY_CASH) {
            pettyCashLedger.reverseExpenseDraw(expense.getPumpId(), expense.getId(), reason, currentUser);
        }
    }

    private static String describe(PumpExpense expense) {
        String text = "Expense #" + expense.getId() + " (" + expense.getCategory() + "): " + expense.getDescription();
        return text.length() <= DESCRIPTION_LIMIT ? text : text.substring(0, DESCRIPTION_LIMIT);
    }
}
