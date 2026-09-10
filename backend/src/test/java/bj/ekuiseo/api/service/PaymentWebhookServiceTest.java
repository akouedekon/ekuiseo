package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.PaymentWebhookEvent;
import bj.ekuiseo.api.domain.enums.WebhookOutcome;
import bj.ekuiseo.api.repository.PaymentWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contrat A.5 : persistance avant traitement, rejeu DUPLICATE non retraite, signature invalide REJECTED, reprise apres ERROR. */
class PaymentWebhookServiceTest {

    private final PaymentWebhookEventRepository repository = mock(PaymentWebhookEventRepository.class);
    private final PaymentWebhookService service = new PaymentWebhookService(repository);
    private final List<PaymentWebhookEvent> stored = new ArrayList<>();
    private final String hash = PaymentWebhookService.hash("{\"transactionId\":\"kk-1\"}");

    @BeforeEach
    void setUp() {
        when(repository.save(any(PaymentWebhookEvent.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(repository.saveAndFlush(any(PaymentWebhookEvent.class))).thenAnswer(inv -> store(inv.getArgument(0)));
        when(repository.findFirstByProviderAndPayloadHashAndOutcomeNot(eq("KKIAPAY"), eq(hash), eq(WebhookOutcome.DUPLICATE)))
                .thenAnswer(inv -> stored.stream().filter(e -> e.getOutcome() != WebhookOutcome.DUPLICATE).findFirst());
        when(repository.findById(any())).thenAnswer(inv -> stored.stream().filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
    }

    private PaymentWebhookEvent store(PaymentWebhookEvent e) {
        if (e.getId() == null) {
            e.setId(UUID.randomUUID());
            stored.add(e);
        }
        return e;
    }

    @Test
    void firstDelivery_isPersistedBeforeProcessing_thenCompleted() {
        PaymentWebhookService.Registration reg = service.register("KKIAPAY", "kk-1", Map.of("transactionId", "kk-1"), hash, true);

        assertThat(reg.decision()).isEqualTo(PaymentWebhookService.Decision.PROCESS);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getOutcome()).isEqualTo(WebhookOutcome.RECEIVED);
        assertThat(stored.get(0).getPayloadHash()).isEqualTo(hash);
        assertThat(stored.get(0).isSignatureValid()).isTrue();

        service.complete(reg.eventId(), WebhookOutcome.PROCESSED, null);
        assertThat(stored.get(0).getOutcome()).isEqualTo(WebhookOutcome.PROCESSED);
        assertThat(stored.get(0).getProcessedAt()).isNotNull();
    }

    /** Meme corps rejoue apres traitement : ligne DUPLICATE, pas de retraitement. */
    @Test
    void replayOfAProcessedBody_isRecordedAsDuplicate_andNotProcessed() {
        PaymentWebhookService.Registration first = service.register("KKIAPAY", "kk-1", Map.of(), hash, true);
        service.complete(first.eventId(), WebhookOutcome.PROCESSED, null);

        PaymentWebhookService.Registration replay = service.register("KKIAPAY", "kk-1", Map.of(), hash, true);

        assertThat(replay.decision()).isEqualTo(PaymentWebhookService.Decision.DUPLICATE);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(1).getOutcome()).isEqualTo(WebhookOutcome.DUPLICATE);
        assertThat(stored.get(0).getOutcome()).isEqualTo(WebhookOutcome.PROCESSED);
    }

    @Test
    void invalidSignature_isRecordedRejected_andNeverProcessed() {
        PaymentWebhookService.Registration reg = service.register("KKIAPAY", "kk-1", Map.of(), hash, false);

        assertThat(reg.decision()).isEqualTo(PaymentWebhookService.Decision.REJECTED);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getOutcome()).isEqualTo(WebhookOutcome.REJECTED);
        assertThat(stored.get(0).isSignatureValid()).isFalse();

        // Le meme corps revient correctement signe : la ligne est reprise et traitee.
        PaymentWebhookService.Registration valid = service.register("KKIAPAY", "kk-1", Map.of(), hash, true);
        assertThat(valid.decision()).isEqualTo(PaymentWebhookService.Decision.PROCESS);
        assertThat(valid.eventId()).isEqualTo(reg.eventId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getOutcome()).isEqualTo(WebhookOutcome.RECEIVED);
    }

    /** Apres une verification non conclusive (ERROR, 503 rendu a l agregateur), le rejeu est retraite sur la meme ligne. */
    @Test
    void replayAfterError_isProcessedAgain() {
        PaymentWebhookService.Registration first = service.register("KKIAPAY", "kk-1", Map.of(), hash, true);
        service.complete(first.eventId(), WebhookOutcome.ERROR, "Verification non conclusive : PENDING");

        PaymentWebhookService.Registration replay = service.register("KKIAPAY", "kk-1", Map.of(), hash, true);

        assertThat(replay.decision()).isEqualTo(PaymentWebhookService.Decision.PROCESS);
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(stored).hasSize(1);
    }

    /** Deux livraisons simultanees : la seconde perd l unicite en base et n est pas traitee. */
    @Test
    void concurrentTwin_isDroppedAsDuplicate() {
        when(repository.saveAndFlush(any(PaymentWebhookEvent.class))).thenThrow(new DataIntegrityViolationException("uq_payment_webhook_events_hash"));

        PaymentWebhookService.Registration reg = service.register("KKIAPAY", "kk-1", Map.of(), hash, true);

        assertThat(reg.decision()).isEqualTo(PaymentWebhookService.Decision.DUPLICATE);
        assertThat(reg.eventId()).isNull();
    }

    @Test
    void hash_isStableAndHex() {
        assertThat(PaymentWebhookService.hash("abc")).hasSize(64).isEqualTo(PaymentWebhookService.hash("abc"));
        assertThat(PaymentWebhookService.hash(null)).isEqualTo(PaymentWebhookService.hash(""));
        assertThat(Optional.of(PaymentWebhookService.hash("a"))).isNotEqualTo(Optional.of(PaymentWebhookService.hash("b")));
    }
}
