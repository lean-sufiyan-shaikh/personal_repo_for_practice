package com.ppms.collection;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.credit.CreditAccountPolicyService;
import com.ppms.credit.CreditAgingResponse;
import com.ppms.credit.CreditAgingService;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditStatus;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is due a reminder today and the record of every reminder given. Accounts are read from the aging view, so a
 * parent account stands for its whole group and the days overdue match the rest of the Credit page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditFollowUpService {

    private static final String UNKNOWN_STAFF_NAME = "Unknown";

    private final CreditAgingService agingService;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditClientAccessService clientAccessService;
    private final CreditReminderRepository reminderRepository;
    private final CreditPaymentPromiseRepository promiseRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final List<ReminderChannel> reminderChannels;
    private final NotificationDedupService notificationDedupService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional(readOnly = true)
    public List<ReminderDueResponse> dueReminders(Long pumpId) {
        return dueReminders(requirePump(pumpId), agingService.agingOfPump(pumpId).clients());
    }

    /** Overdue accounts at a stage not yet reminded in this episode, longest overdue first; a promise not yet due pauses them. */
    List<ReminderDueResponse> dueReminders(PumpLocation pump, List<CreditAgingResponse.Row> accounts) {
        LocalDate today = businessClock.today();
        List<CreditAgingResponse.Row> overdue = accounts.stream().filter(row -> row.daysOverdue() > 0).toList();
        if (overdue.isEmpty()) {
            return List.of();
        }
        Set<Long> pausedClientIds = promiseRepository.findByPumpIdAndStatus(pump.getId(), PromiseStatus.OPEN).stream()
                .filter(promise -> !promise.getPromisedDate().isBefore(today))
                .map(CreditPaymentPromise::getClientId)
                .collect(Collectors.toSet());
        Map<Long, List<CreditReminder>> remindersByClientId = episodeReminders(overdue, today);

        List<ReminderDueResponse> due = new ArrayList<>();
        for (CreditAgingResponse.Row account : overdue) {
            Integer stage = ReminderSchedule.reachedStage(pump.getReminderStageDays(), account.daysOverdue());
            List<CreditReminder> given = remindersByClientId.getOrDefault(account.clientId(), List.of());
            if (pausedClientIds.contains(account.clientId()) || !ReminderSchedule.isDue(stage, given)) {
                continue;
            }
            CreditReminder last = given.stream().max(Comparator.comparing(CreditReminder::getRemindedAt)).orElse(null);
            due.add(new ReminderDueResponse(account.clientId(), account.name(), account.phone(), account.outstanding(),
                    account.daysOverdue(), ReminderSchedule.overdueSince(today, account.daysOverdue()), stage,
                    account.creditStatus() == CreditStatus.ON_HOLD,
                    last != null ? last.getRemindedAt() : null, last != null ? last.getMethod() : null,
                    UpiPaymentLink.forPump(pump, account.outstanding(), account.name())));
        }
        due.sort(Comparator.comparingLong(ReminderDueResponse::daysOverdue).reversed());
        return due;
    }

    /**
     * Tells the managers once a day how many accounts are due a reminder, and once per overdue episode that an
     * account's credit went on hold.
     */
    @Transactional
    public void notifyDailyFollowUp(Long pumpId) {
        PumpLocation pump = requirePump(pumpId);
        List<CreditAgingResponse.Row> accounts = agingService.agingOfPump(pumpId).clients();
        LocalDate today = businessClock.today();
        List<ReminderDueResponse> due = dueReminders(pump, accounts);
        if (!due.isEmpty()) {
            notificationDedupService.insertDaily(pumpId, NotificationType.CREDIT_REMINDERS_DUE, "CREDIT_REMINDERS_DUE",
                    today, "Credit Reminders Due",
                    due.size() + " overdue credit client(s) are due a reminder today. Open Credit → Collections.",
                    Map.of("count", due.size()));
        }
        List<CreditAgingResponse.Row> onHold = accounts.stream()
                .filter(account -> account.creditStatus() == CreditStatus.ON_HOLD)
                .toList();
        for (CreditAgingResponse.Row account : onHold) {
            notificationDedupService.maybeInsert(pumpId, NotificationType.CREDIT_HOLD_PLACED,
                    "CREDIT_HOLD_PLACED:client:" + account.clientId() + ":"
                            + ReminderSchedule.overdueSince(today, account.daysOverdue()),
                    "Credit Put on Hold",
                    account.name() + " is " + account.daysOverdue() + " days overdue, so new credit is on hold until they pay.",
                    Map.of("clientName", account.name(), "daysOverdue", account.daysOverdue(),
                            "clientId", account.clientId()));
        }
        log.info("Daily credit follow-up for pump={}: dueReminders={}, onHold={}", pumpId, due.size(), onHold.size());
    }

    /** Hands the reminder to the channel that carries its method, then records it against the account's running episode. */
    @Transactional
    public ReminderResponse recordReminder(Long pumpId, Long clientId, RecordReminderRequest request, User currentUser) {
        PumpLocation pump = requirePump(pumpId);
        CreditAgingResponse.Row account = accountOf(pumpId, clientId);
        if (account.daysOverdue() <= 0) {
            throw new BusinessException(ErrorCodes.CREDIT_REMINDER_CLIENT_NOT_OVERDUE,
                    Map.of("clientName", account.name()),
                    "'" + account.name() + "' is not overdue, so there is nothing to remind them of.");
        }
        ReminderChannel channel = reminderChannels.stream()
                .filter(candidate -> candidate.carries(request.method()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No reminder channel carries " + request.method()));
        String upiPayLink = UpiPaymentLink.forPump(pump, account.outstanding(), account.name());
        channel.deliver(new ReminderMessage(pumpId, account.clientId(), account.name(), account.phone(),
                account.outstanding(), account.daysOverdue(), upiPayLink), request.method());

        Integer stage = ReminderSchedule.reachedStage(pump.getReminderStageDays(), account.daysOverdue());
        CreditReminder reminder = reminderRepository.save(CreditReminder.builder()
                .pumpId(pumpId)
                .clientId(account.clientId())
                .overdueSince(ReminderSchedule.overdueSince(businessClock.today(), account.daysOverdue()))
                .stageDays(stage != null ? stage : (int) account.daysOverdue())
                .daysOverdue((int) account.daysOverdue())
                .outstanding(account.outstanding())
                .channel(channel.name())
                .method(request.method())
                .note(trimmedOrNull(request.note()))
                .remindedByUserId(currentUser.getId())
                .build());

        auditService.log(pumpId, AuditAction.CREDIT_REMINDER_RECORDED, "CreditReminder", reminder.getId().toString(),
                "Reminded clientId=" + account.clientId() + " by " + request.method() + " at " + account.daysOverdue()
                        + " days overdue, outstanding ₹" + account.outstanding(), currentUser);
        return ReminderResponse.of(reminder, currentUser.getFullName());
    }

    /** Where collecting from the account of this client stands, with its latest promises and reminders. */
    @Transactional(readOnly = true)
    public ClientFollowUpResponse followUpOf(Long pumpId, Long clientId) {
        PumpLocation pump = requirePump(pumpId);
        CreditAgingResponse.Row account = accountOf(pumpId, clientId);
        LocalDate today = businessClock.today();
        boolean overdue = account.daysOverdue() > 0;
        Integer stage = overdue ? ReminderSchedule.reachedStage(pump.getReminderStageDays(), account.daysOverdue()) : null;
        boolean paused = promiseRepository.findByClientIdAndStatus(account.clientId(), PromiseStatus.OPEN)
                .filter(promise -> !promise.getPromisedDate().isBefore(today))
                .isPresent();
        boolean reminderDue = overdue && !paused && ReminderSchedule.isDue(stage,
                episodeReminders(List.of(account), today).getOrDefault(account.clientId(), List.of()));

        List<CreditPaymentPromise> promises = promiseRepository.findTop20ByClientIdOrderByRecordedAtDesc(account.clientId());
        List<CreditReminder> reminders = reminderRepository.findTop20ByClientIdOrderByRemindedAtDesc(account.clientId());
        Set<Long> userIds = new HashSet<>();
        promises.forEach(promise -> userIds.add(promise.getRecordedByUserId()));
        reminders.forEach(reminder -> userIds.add(reminder.getRemindedByUserId()));
        Map<Long, String> nameById = userNames(userIds);

        return new ClientFollowUpResponse(account.clientId(), account.outstanding(), account.daysOverdue(),
                overdue ? ReminderSchedule.overdueSince(today, account.daysOverdue()) : null, stage, reminderDue,
                pump.getCreditHoldDays(), account.creditStatus() == CreditStatus.ON_HOLD,
                UpiPaymentLink.forPump(pump, account.outstanding(), account.name()),
                promises.stream().map(promise -> PromiseResponse.of(promise, account.name(),
                        nameById.getOrDefault(promise.getRecordedByUserId(), UNKNOWN_STAFF_NAME))).toList(),
                reminders.stream().map(reminder -> ReminderResponse.of(reminder,
                        nameById.getOrDefault(reminder.getRemindedByUserId(), UNKNOWN_STAFF_NAME))).toList());
    }

    /** The aging row of the account a client is followed up on: the client itself, or its parent for a sub-account. */
    CreditAgingResponse.Row accountOf(Long pumpId, Long clientId) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        Long holderId = accountPolicyService.limitHolderId(client);
        return agingService.agingOfPump(pumpId).clients().stream()
                .filter(row -> row.clientId().equals(holderId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + holderId));
    }

    Map<Long, String> userNames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
    }

    PumpLocation requirePump(Long pumpId) {
        return pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
    }

    /** The reminders of each account's running episode, in one query. */
    private Map<Long, List<CreditReminder>> episodeReminders(List<CreditAgingResponse.Row> overdue, LocalDate today) {
        Map<Long, LocalDate> overdueSinceById = overdue.stream()
                .filter(row -> row.daysOverdue() > 0)
                .collect(Collectors.toMap(CreditAgingResponse.Row::clientId,
                        row -> ReminderSchedule.overdueSince(today, row.daysOverdue())));
        if (overdueSinceById.isEmpty()) {
            return Map.of();
        }
        LocalDate earliest = overdueSinceById.values().stream().min(Comparator.naturalOrder()).orElseThrow();
        return reminderRepository.findByClientIdInAndOverdueSinceGreaterThanEqual(overdueSinceById.keySet(), earliest)
                .stream()
                .filter(reminder -> reminder.getOverdueSince().equals(overdueSinceById.get(reminder.getClientId())))
                .collect(Collectors.groupingBy(CreditReminder::getClientId));
    }

    private static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
