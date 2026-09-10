package bj.ekuiseo.api.web.controller.admin;

import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.dto.admin.AdminBookingDetailResponse;
import bj.ekuiseo.api.dto.admin.AdminBookingRowResponse;
import bj.ekuiseo.api.service.admin.AdminBookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Reservations du back-office (contrat A.10). Reserve a ROLE_ADMIN. */
@Tag(name = "Admin - Reservations", description = "Recherche et fiche financiere d une reservation (ROLE_ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/bookings")
public class AdminBookingController {

    private final AdminBookingService adminBookingService;

    public AdminBookingController(AdminBookingService adminBookingService) {
        this.adminBookingService = adminBookingService;
    }

    @Operation(summary = "Rechercher des reservations", description = "q : debut d identifiant, telephone ou nom du passager ou du conducteur ; status ; from / to (ISO 8601, to exclusif) sur la date de creation. Page Spring, plus recentes d abord, size <= 100.")
    @GetMapping
    public Page<AdminBookingRowResponse> search(@RequestParam(defaultValue = "") String q,
                                                @RequestParam(required = false) BookingStatus status,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return adminBookingService.search(q, status, from, to, page, size);
    }

    @Operation(summary = "Fiche d une reservation", description = "Montants figes, etat de paiement consolide, especes, paiements, remboursements, evenements de paiement, ecritures du registre et journal d audit.")
    @GetMapping("/{id}")
    public AdminBookingDetailResponse detail(@PathVariable UUID id) {
        return adminBookingService.detail(id);
    }
}
