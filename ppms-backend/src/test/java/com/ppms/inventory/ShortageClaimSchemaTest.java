package com.ppms.inventory;

import com.ppms.fuel.FuelType;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V40: the database itself keeps one claim per delivery, complete settled claims and sane receipt figures. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShortageClaimSchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private UndergroundTankRepository tankRepository;
    @Autowired private TankerDeliveryRepository deliveryRepository;
    @Autowired private ShortageClaimRepository claimRepository;

    private User owner;
    private PumpLocation pump;
    private TankerDelivery delivery;

    @BeforeEach
    void createDelivery() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName("Owner " + unique)
                .phoneNumber("7" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L))
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
        UndergroundTank tank = tankRepository.save(UndergroundTank.builder()
                .pumpId(pump.getId())
                .tankIdentifier("T-" + unique)
                .fuelType(FuelType.PETROL)
                .capacity(new BigDecimal("20000"))
                .currentStock(BigDecimal.ZERO)
                .dipTolerance(new BigDecimal("50"))
                .status(TankStatus.ACTIVE)
                .build());
        delivery = deliveryRepository.save(TankerDelivery.builder()
                .pumpId(pump.getId())
                .tankId(tank.getId())
                .fuelType(FuelType.PETROL)
                .quantityDelivered(new BigDecimal("1000"))
                .costPricePerUnit(new BigDecimal("90"))
                .deliveryDate(TestDates.now())
                .invoiceReference("INV-" + unique)
                .loggedByUserId(owner.getId())
                .build());
    }

    @Test
    void newPumpGetsTheDefaultTolerancesAndAnOutOfRangeToleranceIsRefused() {
        PumpLocation saved = pumpLocationRepository.findById(pump.getId()).orElseThrow();
        assertThat(saved.getReceiptTolerancePercent()).isEqualByComparingTo("0.20");
        assertThat(saved.getDensityTolerance()).isEqualByComparingTo("3.0");

        saved.setReceiptTolerancePercent(new BigDecimal("6"));
        assertThatThrownBy(() -> pumpLocationRepository.saveAndFlush(saved))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deliveryHasAtMostOneClaim() {
        claimRepository.saveAndFlush(raisedClaim());

        assertThatThrownBy(() -> claimRepository.saveAndFlush(raisedClaim()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settledClaimNeedsItsCreditNote() {
        ShortageClaim claim = raisedClaim();
        claim.setStatus(ShortageClaimStatus.SETTLED);
        claim.setResolvedByUserId(owner.getId());
        claim.setResolvedAt(TestDates.now());

        assertThatThrownBy(() -> claimRepository.saveAndFlush(claim))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkedDeliveryNeedsAllItsReceiptFigures() {
        delivery.setReceivedLitres(new BigDecimal("990"));
        delivery.setCheckStatus(DeliveryCheckStatus.SHORT);

        assertThatThrownBy(() -> deliveryRepository.saveAndFlush(delivery))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ShortageClaim raisedClaim() {
        return ShortageClaim.builder()
                .pumpId(pump.getId())
                .tankerDeliveryId(delivery.getId())
                .litresShort(new BigDecimal("10"))
                .pricePerLitre(new BigDecimal("90"))
                .claimAmount(new BigDecimal("900.00"))
                .raisedByUserId(owner.getId())
                .build();
    }
}
