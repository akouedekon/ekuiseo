package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.IdempotencyKey;
import bj.ekuiseo.api.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Contrat A.1 : rejeu, corps different, requete en cours, course entre deux requetes jumelles. */
class IdempotencyServiceTest {

    private final IdempotencyKeyRepository repository = mock(IdempotencyKeyRepository.class);
    private final IdempotencyService service = new IdempotencyService(repository);
    private final UUID userId = UUID.randomUUID();
    private final String route = "POST /api/v1/bookings/" + UUID.randomUUID() + "/cancel";
    private final String hash = IdempotencyService.hashBody("{}".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() {
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(inv -> {
            IdempotencyKey k = inv.getArgument(0);
            k.setId(UUID.randomUUID());
            return k;
        });
    }

    @Test
    void firstCall_registersTheKey_andProceeds() {
        when(repository.findByKeyAndUserIdAndRoute("k-1234567", userId, route)).thenReturn(Optional.empty());

        IdempotencyService.Outcome outcome = service.begin("k-1234567", userId, route, hash);

        assertThat(outcome.decision()).isEqualTo(IdempotencyService.Decision.PROCEED);
        assertThat(outcome.keyId()).isNotNull();
    }

    @Test
    void sameKeyAndBody_replaysTheStoredResponse() {
        IdempotencyKey stored = IdempotencyKey.builder().id(UUID.randomUUID()).key("k").userId(userId).route(route)
                .requestHash(hash).responseStatus(200).responseBody("{\"id\":\"x\"}").build();
        when(repository.findByKeyAndUserIdAndRoute("k", userId, route)).thenReturn(Optional.of(stored));

        IdempotencyService.Outcome outcome = service.begin("k", userId, route, hash);

        assertThat(outcome.decision()).isEqualTo(IdempotencyService.Decision.REPLAY);
        assertThat(outcome.responseStatus()).isEqualTo(200);
        assertThat(outcome.responseBody()).isEqualTo("{\"id\":\"x\"}");
    }

    @Test
    void sameKeyDifferentBody_isAMismatch() {
        IdempotencyKey stored = IdempotencyKey.builder().id(UUID.randomUUID()).key("k").userId(userId).route(route)
                .requestHash("autre").responseStatus(200).responseBody("{}").build();
        when(repository.findByKeyAndUserIdAndRoute("k", userId, route)).thenReturn(Optional.of(stored));

        assertThat(service.begin("k", userId, route, hash).decision()).isEqualTo(IdempotencyService.Decision.MISMATCH);
    }

    @Test
    void keyStillInFlight_isReported() {
        IdempotencyKey stored = IdempotencyKey.builder().id(UUID.randomUUID()).key("k").userId(userId).route(route)
                .requestHash(hash).build();
        when(repository.findByKeyAndUserIdAndRoute("k", userId, route)).thenReturn(Optional.of(stored));

        assertThat(service.begin("k", userId, route, hash).decision()).isEqualTo(IdempotencyService.Decision.IN_FLIGHT);
    }

    /** Deux requetes jumelles : la seconde perd la course en base et relit la cle de la premiere. */
    @Test
    void raceOnInsert_reReadsTheWinner() {
        IdempotencyKey winner = IdempotencyKey.builder().id(UUID.randomUUID()).key("k").userId(userId).route(route)
                .requestHash(hash).build();
        when(repository.findByKeyAndUserIdAndRoute("k", userId, route))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenThrow(new DataIntegrityViolationException("uq_idempotency_keys"));

        assertThat(service.begin("k", userId, route, hash).decision()).isEqualTo(IdempotencyService.Decision.IN_FLIGHT);
    }

    @Test
    void complete_storesStatusAndBody_andPurgeUsesTheRetention() {
        IdempotencyKey stored = IdempotencyKey.builder().id(UUID.randomUUID()).key("k").userId(userId).route(route)
                .requestHash(hash).build();
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        service.complete(stored.getId(), 201, "{\"ok\":true}");
        assertThat(stored.getResponseStatus()).isEqualTo(201);
        assertThat(stored.getResponseBody()).isEqualTo("{\"ok\":true}");

        Instant now = Instant.parse("2026-09-10T12:00:00Z");
        service.purgeExpired(now);
        verify(repository).deleteByCreatedAtBefore(now.minusSeconds(24 * 3600));
    }

    @Test
    void wellFormed_boundsTheKeyLength() {
        assertThat(IdempotencyService.isWellFormed("1234567")).isFalse();
        assertThat(IdempotencyService.isWellFormed("12345678")).isTrue();
        assertThat(IdempotencyService.isWellFormed("x".repeat(64))).isTrue();
        assertThat(IdempotencyService.isWellFormed("x".repeat(65))).isFalse();
        assertThat(IdempotencyService.isWellFormed(null)).isFalse();
        assertThat(IdempotencyService.hashBody(null)).isEqualTo(IdempotencyService.hashBody(new byte[0]));
    }
}
