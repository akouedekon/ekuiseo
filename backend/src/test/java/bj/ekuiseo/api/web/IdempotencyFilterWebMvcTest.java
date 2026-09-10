package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.IdempotencyKey;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.BookingStatus;
import bj.ekuiseo.api.domain.enums.PaymentMethod;
import bj.ekuiseo.api.dto.booking.BookingResponse;
import bj.ekuiseo.api.repository.IdempotencyKeyRepository;
import bj.ekuiseo.api.service.BookingService;
import bj.ekuiseo.api.service.IdempotencyService;
import bj.ekuiseo.api.service.MessageService;
import bj.ekuiseo.api.service.PaymentService;
import bj.ekuiseo.api.web.controller.BookingController;
import bj.ekuiseo.api.web.filter.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat A.1 sur la tranche MVC : rejeu de la reponse memorisee (meme statut, meme corps,
 * {@code Idempotency-Replayed: true}), 422 pour une reutilisation avec un corps different,
 * comportement inchange sans en-tete. Le depot est simule en memoire (pas de base).
 */
@WebMvcTest(controllers = BookingController.class)
@Import({IdempotencyService.class, IdempotencyFilter.class})
class IdempotencyFilterWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private BookingService bookingService;
    @MockitoBean
    private MessageService messageService;
    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private bj.ekuiseo.api.service.BookingPaymentStateService bookingPaymentStateService;
    @MockitoBean
    private bj.ekuiseo.api.service.CashSettlementService cashSettlementService;
    @MockitoBean
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private final Map<UUID, IdempotencyKey> store = new ConcurrentHashMap<>();
    private User passenger;
    private String bearer;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        passenger = activeUser();
        bearer = bearerFor(passenger);
        bookingId = UUID.randomUUID();
        store.clear();
        when(idempotencyKeyRepository.findByKeyAndUserIdAndRoute(any(), any(), any())).thenAnswer(inv ->
                store.values().stream()
                        .filter(k -> k.getKey().equals(inv.getArgument(0)) && k.getUserId().equals(inv.getArgument(1))
                                && k.getRoute().equals(inv.getArgument(2)))
                        .findFirst());
        when(idempotencyKeyRepository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(inv -> {
            IdempotencyKey k = inv.getArgument(0);
            if (k.getId() == null) k.setId(UUID.randomUUID());
            store.put(k.getId(), k);
            return k;
        });
        when(idempotencyKeyRepository.save(any(IdempotencyKey.class))).thenAnswer(inv -> {
            IdempotencyKey k = inv.getArgument(0);
            store.put(k.getId(), k);
            return k;
        });
        when(idempotencyKeyRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    }

    @Test
    void sameKeyTwice_replaysTheFirstResponse_withoutCallingTheServiceAgain() throws Exception {
        when(bookingService.cancelByPassenger(bookingId, passenger.getId()))
                .thenReturn(booking(bookingId, BookingStatus.CANCELLED_BY_PASSENGER));
        String key = UUID.randomUUID().toString();

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.status").value("CANCELLED_BY_PASSENGER"));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED_BY_PASSENGER"));
        verify(bookingService, times(1)).cancelByPassenger(bookingId, passenger.getId());
    }

    /** Une erreur metier (4xx) est memorisee et rejouee elle aussi : le client voit la meme reponse. */
    @Test
    void businessErrorIsReplayedAsWell() throws Exception {
        when(bookingService.cancelByPassenger(bookingId, passenger.getId()))
                .thenThrow(new ConflictException("Le trajet est deja parti"));
        String key = UUID.randomUUID().toString();

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer).header("Idempotency-Key", key))
                .andExpect(status().isConflict());
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer).header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"));
        verify(bookingService, times(1)).cancelByPassenger(bookingId, passenger.getId());
    }

    @Test
    void sameKeyWithADifferentBody_is422() throws Exception {
        when(bookingService.declineByDriver(any(), any(), any()))
                .thenReturn(booking(bookingId, BookingStatus.CANCELLED_BY_DRIVER));
        when(paymentService.initiateDeposit(any(), any(), any())).thenReturn(
                new bj.ekuiseo.api.dto.payment.InitiatePaymentResponse(UUID.randomUUID(), "ref", 1000, "pk", true, Map.of()));
        String key = UUID.randomUUID().toString();
        String path = "/api/v1/bookings/" + bookingId + "/payments/deposit";

        mockMvc.perform(authed(json(post(path), Map.of("provider", "MTN_MOMO", "phone", "+2290197000321")), bearer)
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk());
        mockMvc.perform(authed(json(post(path), Map.of("provider", "MOOV_MONEY", "phone", "+2290197000321")), bearer)
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.com/problems/idempotency-key-reuse"));
        verify(paymentService, times(1)).initiateDeposit(any(), any(), any());
    }

    @Test
    void withoutHeader_nothingChanges_andEachCallReachesTheService() throws Exception {
        when(bookingService.cancelByPassenger(bookingId, passenger.getId()))
                .thenReturn(booking(bookingId, BookingStatus.CANCELLED_BY_PASSENGER));

        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer)).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
        verify(bookingService, times(2)).cancelByPassenger(bookingId, passenger.getId());
        verify(idempotencyKeyRepository, times(0)).saveAndFlush(any());
    }

    @Test
    void malformedKey_is400() throws Exception {
        mockMvc.perform(authed(post("/api/v1/bookings/" + bookingId + "/cancel"), bearer).header("Idempotency-Key", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(bookingService, times(0)).cancelByPassenger(any(), any());
    }

    /** L en-tete n a aucun effet pour un anonyme : la chaine de securite repond 401 avant le filtre. */
    @Test
    void anonymous_withHeader_is401() throws Exception {
        mockMvc.perform(fromNewIp(post("/api/v1/bookings/" + bookingId + "/cancel")).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        verify(idempotencyKeyRepository, times(0)).saveAndFlush(any());
    }

    private static BookingResponse booking(UUID id, BookingStatus status) {
        return new BookingResponse(id, UUID.randomUUID(), UUID.randomUUID(), 1, 2500, 200, status,
                PaymentMethod.MOMO_DEPOSIT, Instant.now(), null, null, null, null, null, null);
    }
}
