package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.IdempotencyKey;
import bj.ekuiseo.api.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotence des ecritures financieres (contrat A.1, V26). Le filtre
 * {@link bj.ekuiseo.api.web.filter.IdempotencyFilter} appelle {@link #begin} avant de laisser
 * passer la requete, puis {@link #complete} avec la reponse produite, ou {@link #abandon} si
 * la reponse n est pas memorisable (5xx : le client doit pouvoir reessayer avec la meme cle).
 *
 * <p>Regle : meme cle + meme utilisateur + meme route + meme corps -> la reponse memorisee est
 * rejouee ; meme cle avec un corps different -> refus (422) ; requete encore en cours sous la
 * meme cle -> 409. Chaque acces est sa propre transaction (REQUIRES_NEW) : la cle est visible
 * des autres requetes avant que l ecriture metier ne commence.</p>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Longueur admise de l en-tete Idempotency-Key (le front envoie un UUID v4, 36 caracteres). */
    public static final int KEY_MIN_LENGTH = 8;
    public static final int KEY_MAX_LENGTH = 64;
    /** Duree de conservation des cles. */
    public static final long RETENTION_HOURS = 24;

    public enum Decision { PROCEED, REPLAY, MISMATCH, IN_FLIGHT }

    /** Issue de {@link #begin} : que faire de la requete, et la reponse memorisee en cas de rejeu. */
    public record Outcome(Decision decision, UUID keyId, Integer responseStatus, String responseBody) {
        static Outcome proceed(UUID keyId) {
            return new Outcome(Decision.PROCEED, keyId, null, null);
        }
    }

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    public static boolean isWellFormed(String key) {
        if (key == null) return false;
        String trimmed = key.trim();
        return trimmed.length() >= KEY_MIN_LENGTH && trimmed.length() <= KEY_MAX_LENGTH;
    }

    /** SHA-256 hexadecimal du corps de la requete (corps vide -> hash de la chaine vide). */
    public static String hashBody(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Enregistre la cle (reponse en attente) ou renvoie ce qu il faut en faire : rejeu de la
     * reponse memorisee, refus pour corps different, ou 409 si la premiere requete est encore en
     * cours. La course entre deux requetes simultanees est tranchee par l unicite en base.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome begin(String key, UUID userId, String route, String requestHash) {
        Optional<IdempotencyKey> existing = repository.findByKeyAndUserIdAndRoute(key, userId, route);
        if (existing.isPresent()) {
            return evaluate(existing.get(), requestHash);
        }
        try {
            IdempotencyKey saved = repository.saveAndFlush(IdempotencyKey.builder()
                    .key(key).userId(userId).route(route).requestHash(requestHash).build());
            return Outcome.proceed(saved.getId());
        } catch (DataIntegrityViolationException race) {
            // Une requete jumelle vient d inscrire la cle : on relit et on tranche comme pour un rejeu.
            return repository.findByKeyAndUserIdAndRoute(key, userId, route)
                    .map(k -> evaluate(k, requestHash))
                    .orElse(new Outcome(Decision.IN_FLIGHT, null, null, null));
        }
    }

    private static Outcome evaluate(IdempotencyKey stored, String requestHash) {
        if (!stored.getRequestHash().equals(requestHash)) {
            return new Outcome(Decision.MISMATCH, stored.getId(), null, null);
        }
        if (stored.getResponseStatus() == null) {
            return new Outcome(Decision.IN_FLIGHT, stored.getId(), null, null);
        }
        return new Outcome(Decision.REPLAY, stored.getId(), stored.getResponseStatus(), stored.getResponseBody());
    }

    /** Memorise la reponse produite (statut et corps JSON) pour les rejeux a venir. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID keyId, int status, String responseBody) {
        repository.findById(keyId).ifPresent(k -> {
            k.setResponseStatus(status);
            k.setResponseBody(responseBody);
            repository.save(k);
        });
    }

    /** Reponse non memorisable (erreur serveur, corps non JSON) : la cle est liberee pour un nouvel essai. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(UUID keyId) {
        try {
            repository.deleteById(keyId);
        } catch (RuntimeException ex) {
            log.warn("Cle d idempotence {} non liberee", keyId, ex);
        }
    }

    /** Purge des cles plus vieilles que {@link #RETENTION_HOURS}. */
    @Transactional
    public int purgeExpired(Instant now) {
        return repository.deleteByCreatedAtBefore(now.minusSeconds(RETENTION_HOURS * 3600));
    }
}
