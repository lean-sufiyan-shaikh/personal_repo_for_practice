package com.ppms.dayclose;

import com.ppms.common.time.DefaultBusinessClock;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.support.PostgresTestContainerConfig;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V42: one close per pump and day, consistent approval fields, unique versions, and the new enum values. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DayCloseSchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private DayCloseRepository dayCloseRepository;
    @Autowired private DayCloseVersionRepository versionRepository;
    @Autowired private DayCloseCommentRepository commentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private PumpLocation pump;

    @BeforeEach
    void createPump() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName("Owner " + unique)
                .phoneNumber("8" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L))
                .passwordHash("hash")
                .role(UserRole.OWNER)
                .status(UserStatus.ACTIVE)
                .dateOfJoining(TestDates.today())
                .build());
        pump = pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name("Pump " + unique)
                .address("Address")
                .maxDuCount(4)
                .build());
    }

    @Test
    void aDayIsClosedOncePerPump() {
        LocalDate day = TestDates.today().minusDays(3);
        dayCloseRepository.saveAndFlush(close(day, DayCloseStatus.CLOSED));

        assertThatThrownBy(() -> dayCloseRepository.saveAndFlush(close(day, DayCloseStatus.CLOSED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approvedDayNeedsWhoApprovedItAndWhenAndOnlyAnApprovedDayHasThem() {
        DayClose approvedWithoutApprover = close(TestDates.today().minusDays(4), DayCloseStatus.APPROVED);
        assertThatThrownBy(() -> dayCloseRepository.saveAndFlush(approvedWithoutApprover))
                .isInstanceOf(DataIntegrityViolationException.class);

        DayClose closedWithApprover = close(TestDates.today().minusDays(5), DayCloseStatus.CLOSED);
        closedWithApprover.setApprovedByUserId(owner.getId());
        closedWithApprover.setApprovedAt(TestDates.now());
        assertThatThrownBy(() -> dayCloseRepository.saveAndFlush(closedWithApprover))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionNumberIsUniquePerDayAndCommentsNeedText() {
        DayClose close = dayCloseRepository.saveAndFlush(close(TestDates.today().minusDays(6), DayCloseStatus.CLOSED));
        versionRepository.saveAndFlush(version(close, 1));

        assertThatThrownBy(() -> versionRepository.saveAndFlush(version(close, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> commentRepository.saveAndFlush(DayCloseComment.builder()
                .dayCloseId(close.getId())
                .kind(DayCloseCommentKind.QUESTION)
                .message("   ")
                .authorUserId(owner.getId())
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storedSummaryReadsBackUnchanged() {
        DayClose close = dayCloseRepository.saveAndFlush(close(TestDates.today().minusDays(7), DayCloseStatus.CLOSED));
        DayCloseVersion saved = versionRepository.saveAndFlush(version(close, 1));

        DaySummary readBack = versionRepository.findById(saved.getId()).orElseThrow().getSummary();
        assertThat(readBack.businessDate()).isEqualTo(close.getBusinessDate());
        assertThat(readBack.overallFlag()).isEqualTo(FlagColour.AMBER);
        assertThat(readBack.sales().flag().reasons()).singleElement()
                .satisfies(reason -> assertThat(reason.code()).isEqualTo("SALES_NO_CLOSED_SHIFTS"));
        assertThat(versionRepository.findCurrentVersions(List.of(close.getId()))).hasSize(1);
    }

    @Test
    void guardRefusesClosedAndApprovedDaysButNotReopenedOnes() {
        LocalDate closedDay = TestDates.today().minusDays(8);
        LocalDate reopenedDay = TestDates.today().minusDays(9);
        dayCloseRepository.saveAndFlush(close(closedDay, DayCloseStatus.CLOSED));
        dayCloseRepository.saveAndFlush(close(reopenedDay, DayCloseStatus.REOPENED));
        ClosedDayGuard closedDayGuard = new ClosedDayGuard(dayCloseRepository, new DefaultBusinessClock("Asia/Kolkata"));

        closedDayGuard.requireOpen(pump.getId(), List.of(reopenedDay, TestDates.today().minusDays(10)));
        assertThatThrownBy(() -> closedDayGuard.requireOpen(pump.getId(), List.of(reopenedDay, closedDay)))
                .hasMessageContaining(closedDay + " is closed");
    }

    @Test
    void newEnumValuesExist() {
        assertThat(jdbcTemplate.queryForList("SELECT unnest(enum_range(NULL::notification_type))::text", String.class))
                .contains("DAY_CLOSED", "DAY_REOPENED", "DAY_CLOSE_COMMENT");
        assertThat(jdbcTemplate.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                .contains("DAY_CLOSED", "DAY_APPROVED", "DAY_REOPENED", "DAY_CLOSE_COMMENTED");
    }

    private DayClose close(LocalDate day, DayCloseStatus status) {
        return DayClose.builder()
                .pumpId(pump.getId())
                .businessDate(day)
                .status(status)
                .currentVersion(1)
                .closedByUserId(owner.getId())
                .closedAt(TestDates.now())
                .build();
    }

    private DayCloseVersion version(DayClose close, int number) {
        return DayCloseVersion.builder()
                .dayCloseId(close.getId())
                .version(number)
                .summary(DaySummaryBuilderTest.emptyDaySummary(close.getBusinessDate()))
                .closedByUserId(owner.getId())
                .closedAt(TestDates.now())
                .build();
    }
}
