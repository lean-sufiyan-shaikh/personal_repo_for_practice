package com.ppms.fleet;

import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.VehicleCheckMode;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The V45 schema keeps one active vehicle per registration at a pump, registrations normalised and checks known. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FleetVehicleSchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private CreditClientRepository clientRepository;
    @Autowired private ClientVehicleRepository vehicleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private PumpLocation pump;
    private CreditClient client;

    @BeforeEach
    void createPumpAndClient() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User owner = userRepository.save(User.builder()
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
        client = clientRepository.save(CreditClient.builder().pumpId(pump.getId()).name("Client " + unique).build());
    }

    @Test
    void newClientStartsWithTheVehicleCheckOff() {
        assertThat(clientRepository.findById(client.getId()).orElseThrow().getVehicleCheck())
                .isEqualTo(VehicleCheckMode.OFF);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE credit_clients SET vehicle_check = 'STRICT' WHERE id = ?",
                client.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneActiveVehiclePerRegistrationAtAPumpButDeactivatedOnesMayRepeat() {
        vehicleRepository.save(vehicle("MH12AB1234", false));
        vehicleRepository.save(vehicle("MH12AB1234", false));
        vehicleRepository.save(vehicle("MH12AB1234", true));

        assertThatThrownBy(() -> vehicleRepository.save(vehicle("MH12AB1234", true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void registrationMustBeNormalisedAndFuelTypesAndLimitsValid() {
        assertThatThrownBy(() -> vehicleRepository.save(vehicle("mh12ab1234", true)))
                .isInstanceOf(DataIntegrityViolationException.class);
        ClientVehicle unknownFuel = vehicle("MH14CD5678", true);
        unknownFuel.setAllowedFuelTypes(new String[]{"KEROSENE"});
        assertThatThrownBy(() -> vehicleRepository.save(unknownFuel)).isInstanceOf(DataIntegrityViolationException.class);
        ClientVehicle zeroLimit = vehicle("MH14CD5679", true);
        zeroLimit.setMonthlyLitreLimit(BigDecimal.ZERO);
        assertThatThrownBy(() -> vehicleRepository.save(zeroLimit)).isInstanceOf(DataIntegrityViolationException.class);
        ClientVehicle diesel = vehicle("MH14CD5680", true);
        diesel.setAllowedFuelTypes(new String[]{"DIESEL", "SPEED_DIESEL"});
        assertThat(vehicleRepository.save(diesel).getAllowedFuelTypes()).containsExactly("DIESEL", "SPEED_DIESEL");
    }

    private ClientVehicle vehicle(String registration, boolean active) {
        return ClientVehicle.builder()
                .pumpId(pump.getId())
                .clientId(client.getId())
                .registration(registration)
                .isActive(active)
                .build();
    }
}
