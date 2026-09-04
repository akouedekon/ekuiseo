package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.admin.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Validation des vehicules par le back-office. Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Vehicules", description = "Reserve au back-office (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/vehicles")
public class AdminVehicleController {

    private final AdminUserService adminUserService;
    private final CurrentUser currentUser;

    public AdminVehicleController(AdminUserService adminUserService, CurrentUser currentUser) {
        this.adminUserService = adminUserService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Valider un vehicule")
    @PostMapping("/{id}/verify")
    public ResponseEntity<Void> verify(@PathVariable UUID id) {
        adminUserService.verifyVehicle(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
