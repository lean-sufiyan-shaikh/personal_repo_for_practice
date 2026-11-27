package com.ppms.collection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface CreditReminderRepository extends JpaRepository<CreditReminder, Long> {

    /** Reminders of episodes that began on or after the date, enough to cover the episodes now running. */
    List<CreditReminder> findByClientIdInAndOverdueSinceGreaterThanEqual(Collection<Long> clientIds, LocalDate overdueSince);

    List<CreditReminder> findTop20ByClientIdOrderByRemindedAtDesc(Long clientId);
}
