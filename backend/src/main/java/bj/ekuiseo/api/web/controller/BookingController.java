package bj.ekuiseo.api.web.controller;

import bj.ekuiseo.api.dto.booking.BookingDetailResponse;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.dto.message.MessageResponse;
import bj.ekuiseo.api.dto.message.SendMessageRequest;
import bj.ekuiseo.api.dto.payment.InitiateDepositRequest;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.security.CurrentUser;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.MessageService;
import bj.ekuiseo.api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Reservations", description = "Consultation, annulation et messagerie liees aux reservations")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final MessageService messageService;
    private final PaymentService paymentService;
    private final CurrentUser currentUser;

    public BookingController(BookingService bookingService, MessageService messageService,
                              PaymentService paymentService, CurrentUser currentUser) {
        this.bookingService = bookingService;
        this.messageService = messageService;
        this.paymentService = paymentService;
        this.currentUser = currentUser;
    }

    /**
     * Mes reservations (en tant que passager), enrichies du trajet et du plan de
     * paiement. Le parametre {@code expand} est accepte pour coller au contrat
     * front (voir BookingDetailResponse) mais l'enrichissement est en realite
     * toujours applique : la requete sous-jacente le fournit sans surcout.
     */
    @Operation(summary = "Mes reservations (en tant que passager)", description = "expand=trip,paymentPlan : accepte pour compatibilite, toujours applique.")
    @GetMapping
    public List<BookingDetailResponse> myBookings(
            @RequestParam(required = false) String expand) {
        return bookingService.myBookingsDetailed(currentUser.id());
    }

    @Operation(summary = "Consulter une reservation", description = "Reserve au passager ou au conducteur du trajet concerne. expand=trip,paymentPlan accepte pour compatibilite, toujours applique.")
    @GetMapping("/{id}")
    public BookingDetailResponse get(@PathVariable UUID id, @RequestParam(required = false) String expand) {
        return bookingService.getBookingDetailed(id, currentUser.id());
    }

    @Operation(summary = "Annuler ma reservation", description = "Remboursement selon le bareme : integral si plus de 24h avant le depart, 50% retenus si moins de 24h, rien si apres le depart.")
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id) {
        return bookingService.cancelByPassenger(id, currentUser.id());
    }

    @Operation(summary = "Initier l'acompte mobile money de cette reservation", description = "Voie normale (remplace /api/v1/payments/kkiapay/initiate, conserve pour compatibilite). Renvoie la meme charge utile, a transmettre au widget Kkiapay.")
    @PostMapping("/{id}/payments/deposit")
    public InitiatePaymentResponse initiateDeposit(@PathVariable UUID id, @Valid @RequestBody InitiateDepositRequest req) {
        return paymentService.initiateDeposit(id, currentUser.id(), req);
    }

    @Operation(summary = "Historique des messages de cette reservation")
    @GetMapping("/{id}/messages")
    public List<MessageResponse> messages(@PathVariable UUID id) {
        return messageService.list(id, currentUser.id());
    }

    @Operation(summary = "Envoyer un message au conducteur/passager de cette reservation")
    @PostMapping("/{id}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messageService.send(id, currentUser.id(), req));
    }
}
