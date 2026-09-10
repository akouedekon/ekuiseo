package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Paging;
import bj.ekuiseo.api.domain.PaymentWebhookEvent;
import bj.ekuiseo.api.domain.enums.WebhookOutcome;
import bj.ekuiseo.api.dto.payment.WebhookEventResponse;
import bj.ekuiseo.api.repository.PaymentWebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistance des webhooks recus AVANT leur traitement (contrat A.5, V26), dans une transaction
 * propre (REQUIRES_NEW) : la trace existe meme si le traitement echoue ensuite.
 * <ul>
 *   <li>corps deja traite (PROCESSED / IGNORED) : nouvelle ligne DUPLICATE, pas de retraitement ;</li>
 *   <li>corps revenu apres ERROR / REJECTED / RECEIVED : la meme ligne est reprise et retraitee ;</li>
 *   <li>signature invalide : ligne REJECTED, jamais traitee.</li>
 * </ul>
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    public enum Decision { PROCESS, DUPLICATE, REJECTED }

    public record Registration(Decision decision, UUID eventId) {
    }

    private final PaymentWebhookEventRepository repository;

    public PaymentWebhookService(PaymentWebhookEventRepository repository) {
        this.repository = repository;
    }

    public static String hash(String rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Registration register(String provider, String providerTxId, Map<String, Object> payload, String payloadHash,
                                 boolean signatureValid) {
        Optional<PaymentWebhookEvent> existing = repository.findFirstByProviderAndPayloadHashAndOutcomeNot(
                provider, payloadHash, WebhookOutcome.DUPLICATE);
        if (existing.isPresent()) {
            PaymentWebhookEvent event = existing.get();
            if (event.getOutcome() == WebhookOutcome.PROCESSED || event.getOutcome() == WebhookOutcome.IGNORED) {
                PaymentWebhookEvent duplicate = repository.save(PaymentWebhookEvent.builder()
                        .provider(provider).providerTxId(providerTxId).payload(payload).payloadHash(payloadHash)
                        .signatureValid(signatureValid).outcome(WebhookOutcome.DUPLICATE).processedAt(Instant.now())
                        .error("Corps deja traite le " + event.getProcessedAt()).build());
                log.info("Webhook rejoue (deja {}) pour transactionId={} : enregistre DUPLICATE, non retraite",
                        event.getOutcome(), providerTxId);
                return new Registration(Decision.DUPLICATE, duplicate.getId());
            }
            // ERROR, REJECTED ou RECEIVED : on reprend la meme ligne.
            event.setSignatureValid(signatureValid);
            event.setOutcome(signatureValid ? WebhookOutcome.RECEIVED : WebhookOutcome.REJECTED);
            event.setError(signatureValid ? null : "Signature invalide");
            event.setProcessedAt(signatureValid ? null : Instant.now());
            repository.save(event);
            return new Registration(signatureValid ? Decision.PROCESS : Decision.REJECTED, event.getId());
        }
        try {
            PaymentWebhookEvent saved = repository.saveAndFlush(PaymentWebhookEvent.builder()
                    .provider(provider).providerTxId(providerTxId).payload(payload).payloadHash(payloadHash)
                    .signatureValid(signatureValid)
                    .outcome(signatureValid ? WebhookOutcome.RECEIVED : WebhookOutcome.REJECTED)
                    .processedAt(signatureValid ? null : Instant.now())
                    .error(signatureValid ? null : "Signature invalide").build());
            return new Registration(signatureValid ? Decision.PROCESS : Decision.REJECTED, saved.getId());
        } catch (DataIntegrityViolationException race) {
            // Deux livraisons simultanees du meme corps : la seconde n est pas traitee.
            log.info("Webhook jumeau en cours de traitement pour transactionId={} : ignore", providerTxId);
            return new Registration(Decision.DUPLICATE, null);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID eventId, WebhookOutcome outcome, String error) {
        if (eventId == null) return;
        repository.findById(eventId).ifPresent(event -> {
            event.setOutcome(outcome);
            event.setProcessedAt(Instant.now());
            event.setError(error == null ? null : error.length() > 500 ? error.substring(0, 500) : error);
            repository.save(event);
        });
    }

    @Transactional(readOnly = true)
    public Page<WebhookEventResponse> list(int page, int size) {
        return repository.findAllByOrderByReceivedAtDesc(Paging.of(page, size)).map(WebhookEventResponse::from);
    }
}
