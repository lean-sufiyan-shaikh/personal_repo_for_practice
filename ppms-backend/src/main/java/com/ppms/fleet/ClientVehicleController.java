package com.ppms.fleet;

import com.ppms.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** A credit client's approved vehicles and the account's vehicle check. */
@RestController
@RequestMapping("/api/pumps/{pumpId}/credit-clients/{clientId}")
@RequiredArgsConstructor
public class ClientVehicleController {

    private final ClientVehicleService vehicleService;

    /** Every vehicle the client's account screens show, deactivated ones included, with this month's use. */
    @GetMapping("/vehicles")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ClientVehiclesResponse getVehicles(@PathVariable Long pumpId, @PathVariable Long clientId) {
        return vehicleService.vehiclesOf(pumpId, clientId, false);
    }

    /** The active vehicles a credit entry for the client may name, with what is left of their limits this month. */
    @GetMapping("/vehicle-picker")
    public ClientVehiclesResponse getVehiclePicker(@PathVariable Long pumpId, @PathVariable Long clientId) {
        return vehicleService.vehiclesOf(pumpId, clientId, true);
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ClientVehicleResponse addVehicle(@PathVariable Long pumpId, @PathVariable Long clientId,
                                            @Valid @RequestBody SaveVehicleRequest request,
                                            @AuthenticationPrincipal User currentUser) {
        return vehicleService.addVehicle(pumpId, clientId, request, currentUser);
    }

    @PutMapping("/vehicles/{vehicleId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ClientVehicleResponse updateVehicle(@PathVariable Long pumpId, @PathVariable Long clientId,
                                               @PathVariable Long vehicleId,
                                               @Valid @RequestBody SaveVehicleRequest request,
                                               @AuthenticationPrincipal User currentUser) {
        return vehicleService.updateVehicle(pumpId, clientId, vehicleId, request, currentUser);
    }

    @PatchMapping("/vehicles/{vehicleId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ClientVehicleResponse setVehicleStatus(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                  @PathVariable Long vehicleId,
                                                  @Valid @RequestBody UpdateVehicleStatusRequest request,
                                                  @AuthenticationPrincipal User currentUser) {
        return vehicleService.setVehicleActive(pumpId, clientId, vehicleId, request.active(), currentUser);
    }

    @PutMapping("/vehicle-check")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER')")
    public ClientVehiclesResponse setVehicleCheck(@PathVariable Long pumpId, @PathVariable Long clientId,
                                                  @Valid @RequestBody UpdateVehicleCheckRequest request,
                                                  @AuthenticationPrincipal User currentUser) {
        return vehicleService.setVehicleCheck(pumpId, clientId, request, currentUser);
    }
}
