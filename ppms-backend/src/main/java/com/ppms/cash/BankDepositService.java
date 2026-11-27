package com.ppms.cash;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.common.time.DateRangeBounds;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bank deposits take cash out of the safe: each one writes a BANK_DEPOSIT cash event in the same transaction, so the
 * safe's ledger and the deposit list always agree. The slip reference is required; a photo or PDF of the slip may
 * be attached afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankDepositService {

    static final int MAX_SLIP_BYTES = 5 * 1024 * 1024;
    private static final String ENTITY_NAME = "BankDeposit";

    private final BankDepositRepository depositRepository;
    private final BankDepositSlipRepository slipRepository;
    private final CashEventRepository cashEventRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final ClosedDayGuard closedDayGuard;

    /** The pump row is locked, as for a manual cash-out, so two deposits cannot both spend the same balance. */
    @Transactional
    public BankDepositResponse recordDeposit(Long pumpId, RecordBankDepositRequest request, User currentUser) {
        pumpLocationRepository.findByIdForUpdate(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        if (request.depositDate().isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.BANK_DEPOSIT_DATE_IN_FUTURE, Map.of(),
                    "The deposit date cannot be in the future.");
        }
        closedDayGuard.requireOpen(pumpId, request.depositDate());
        String slipReference = request.slipReference().trim();
        if (depositRepository.existsByPumpIdAndSlipReferenceIgnoreCase(pumpId, slipReference)) {
            throw new BusinessException(ErrorCodes.BANK_DEPOSIT_SLIP_REFERENCE_USED, Map.of("slipReference", slipReference),
                    "A deposit with slip reference '" + slipReference + "' is already recorded.");
        }
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        requireSafeCovers(pumpId, request.depositDate(), amount);
        String bankAccount = request.bankAccount().trim();
        CashEvent event = cashEventRepository.save(CashEvent.builder()
                .pumpId(pumpId)
                .eventType(CashEventType.BANK_DEPOSIT)
                .amount(amount)
                .description("Bank deposit to " + bankAccount + " (slip " + slipReference + ")")
                .eventDate(request.depositDate())
                .recordedByUserId(currentUser.getId())
                .build());
        BankDeposit deposit = depositRepository.save(BankDeposit.builder()
                .pumpId(pumpId)
                .amount(amount)
                .bankAccount(bankAccount)
                .depositDate(request.depositDate())
                .slipReference(slipReference)
                .notes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim())
                .cashEventId(event.getId())
                .recordedByUserId(currentUser.getId())
                .build());

        auditService.log(pumpId, AuditAction.BANK_DEPOSIT_RECORDED, ENTITY_NAME, deposit.getId().toString(),
                "Bank deposit of ₹" + amount + " to " + bankAccount + " on " + request.depositDate() + ", slip " + slipReference,
                currentUser);
        log.info("Bank deposit recorded: pump={}, deposit={}, amount={}, by={}", pumpId, deposit.getId(), amount, currentUser.getId());
        return toResponse(deposit, false, currentUser.getFullName());
    }

    /**
     * The cash must be in the safe at the end of the deposit date, and taking it out then must not leave any later day
     * ending below zero, so a back-dated deposit cannot spend cash that only arrived afterwards.
     */
    private void requireSafeCovers(Long pumpId, LocalDate depositDate, BigDecimal amount) {
        BigDecimal balanceThatDay = cashEventRepository.computeBalanceAtEndOf(pumpId, depositDate).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(balanceThatDay) > 0) {
            throw new BusinessException(ErrorCodes.BANK_DEPOSIT_EXCEEDS_SAFE_BALANCE,
                    Map.of("amount", amount.toPlainString(), "balance", balanceThatDay.toPlainString()),
                    "A deposit of ₹" + amount + " is more than the ₹" + balanceThatDay + " the safe held at the end of "
                            + depositDate + ".");
        }
        BigDecimal lowestLater = cashEventRepository.findLowestEndOfDayBalanceFrom(pumpId, depositDate);
        if (lowestLater != null && amount.compareTo(lowestLater) > 0) {
            BigDecimal lowest = lowestLater.setScale(2, RoundingMode.HALF_UP);
            throw new BusinessException(ErrorCodes.BANK_DEPOSIT_LEAVES_SAFE_NEGATIVE,
                    Map.of("amount", amount.toPlainString(), "lowestBalance", lowest.toPlainString(),
                            "depositDate", depositDate.toString()),
                    "A deposit of ₹" + amount + " dated " + depositDate + " would leave the safe below zero on a later "
                            + "day, when it held only ₹" + lowest + ". Record it on a later date.");
        }
    }

    @Transactional(readOnly = true)
    public BankDepositListResponse listDeposits(Long pumpId, LocalDate from, LocalDate to, String bankAccount, int page, int size) {
        LocalDate fromDate = DateRangeBounds.startOrEarliest(from);
        LocalDate toDate = DateRangeBounds.endOrLatest(to);
        DateRangeBounds.requireStartNotAfterEnd(fromDate, toDate);
        String accountFilter = bankAccount == null || bankAccount.isBlank() ? null : bankAccount.trim();

        Page<BankDeposit> deposits = depositRepository.findForList(pumpId, fromDate, toDate, accountFilter, PageRequests.of(page, size));
        Set<Long> depositIds = deposits.getContent().stream().map(BankDeposit::getId).collect(Collectors.toSet());
        Set<Long> withSlip = depositIds.isEmpty() ? Set.of() : slipRepository.findDepositIdsWithSlip(depositIds);
        Set<Long> userIds = deposits.getContent().stream().map(BankDeposit::getRecordedByUserId).collect(Collectors.toSet());
        Map<Long, String> nameById = userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        BankDepositRepository.DepositTotal total = depositRepository.totalForList(pumpId, fromDate, toDate, accountFilter);

        return new BankDepositListResponse(
                PagedResponse.of(deposits.map(deposit -> toResponse(deposit, withSlip.contains(deposit.getId()),
                        nameById.getOrDefault(deposit.getRecordedByUserId(), "Unknown")))),
                total.getDepositCount(), total.getAmount().setScale(2, RoundingMode.HALF_UP));
    }

    /** Attaches or replaces the slip. The file's own first bytes decide its type, never the name or the browser's claim. */
    @Transactional
    public BankDepositResponse attachSlip(Long pumpId, Long depositId, MultipartFile file, User currentUser) {
        BankDeposit deposit = findDeposit(pumpId, depositId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCodes.DEPOSIT_SLIP_EMPTY, Map.of(), "Choose a photo or PDF of the deposit slip.");
        }
        if (file.getSize() > MAX_SLIP_BYTES) {
            throw new BusinessException(ErrorCodes.DEPOSIT_SLIP_TOO_LARGE, Map.of("maxMb", MAX_SLIP_BYTES / (1024 * 1024)),
                    "The slip file must be at most " + MAX_SLIP_BYTES / (1024 * 1024) + " MB.");
        }
        byte[] content = readBytes(file, depositId);
        String contentType = SlipFileType.detect(content)
                .orElseThrow(() -> new BusinessException(ErrorCodes.DEPOSIT_SLIP_TYPE_NOT_ALLOWED, Map.of(),
                        "The slip must be a JPEG or PNG photo or a PDF."));

        slipRepository.save(BankDepositSlip.builder()
                .bankDepositId(deposit.getId())
                .contentType(contentType)
                .fileName(SlipFileType.safeFileName(file.getOriginalFilename(), deposit.getId(), contentType))
                .sizeBytes(content.length)
                .content(content)
                .uploadedByUserId(currentUser.getId())
                .uploadedAt(businessClock.now())
                .build());
        auditService.log(pumpId, AuditAction.DEPOSIT_SLIP_ATTACHED, ENTITY_NAME, deposit.getId().toString(),
                "Slip " + contentType + " (" + content.length + " bytes) attached to the bank deposit with slip " + deposit.getSlipReference(),
                currentUser);
        log.info("Deposit slip attached: pump={}, deposit={}, type={}, bytes={}, by={}",
                pumpId, depositId, contentType, content.length, currentUser.getId());
        return toResponse(deposit, true, nameOf(deposit.getRecordedByUserId()));
    }

    @Transactional(readOnly = true)
    public BankDepositSlip findSlip(Long pumpId, Long depositId) {
        BankDeposit deposit = findDeposit(pumpId, depositId);
        return slipRepository.findById(deposit.getId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DEPOSIT_SLIP, "No slip attached to this deposit"));
    }

    private BankDeposit findDeposit(Long pumpId, Long depositId) {
        return depositRepository.findByIdAndPumpId(depositId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.BANK_DEPOSIT, "Bank deposit not found"));
    }

    private static byte[] readBytes(MultipartFile file, Long depositId) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded slip for deposit " + depositId, e);
        }
    }

    private String nameOf(Long userId) {
        return userRepository.findById(userId).map(User::getFullName).orElse("Unknown");
    }

    private static BankDepositResponse toResponse(BankDeposit deposit, boolean hasSlip, String recordedByName) {
        return new BankDepositResponse(deposit.getId(), deposit.getAmount(), deposit.getBankAccount(), deposit.getDepositDate(),
                deposit.getSlipReference(), deposit.getNotes(), hasSlip, recordedByName, deposit.getCreatedAt());
    }
}
