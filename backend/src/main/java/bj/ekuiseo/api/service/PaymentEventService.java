package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.Payment;
import bj.ekuiseo.api.domain.PaymentEvent;
import bj.ekuiseo.api.domain.enums.PaymentEventSource;
import bj.ekuiseo.api.domain.enums.PaymentStatus;
import bj.ekuiseo.api.dto.payment.PaymentEventResponse;
import bj.ekuiseo.api.repository.PaymentEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Journal des evenements de paiement (contrat A.5, V26) : une ligne par transition ou tentative,
 * ecrite dans la transaction de l evenement (jamais perdue si elle aboutit, jamais orpheline si
 * elle echoue). Les details ne contiennent ni secret ni numero complet : identifiants,
 * montants, statuts, operateur et decision.
 */
@Service
public class PaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventService.class);

    public static final String INITIATED = "INITIATED";
    public static final String VERIFIED = "VERIFIED";
    public static final String REPLAY_IGNORED = "REPLAY_IGNORED";
    public static final String CONFIRM_REDIRECTED = "CONFIRM_REDIRECTED";
    public static final String ABANDONED = "ABANDONED";
    public static final String REFUND_REQUESTED = "REFUND_REQUESTED";
    public static final String REFUND_MANUAL_REVIEW = "REFUND_MANUAL_REVIEW";
    public static final String REFUND_PROCESSING = "REFUND_PROCESSING";
    public static final String REFUND_SUCCEEDED = "REFUND_SUCCEEDED";
    public static final String REFUND_FAILED = "REFUND_FAILED";
    public static final String REFUND_RETRIED = "REFUND_RETRIED";
    public static final String REFUND_MARKED_SUCCEEDED = "REFUND_MARKED_SUCCEEDED";
    public static final String REFUND_ABANDONED = "REFUND_ABANDONED";

    private final PaymentEventRepository repository;

    public PaymentEventService(PaymentEventRepository repository) {
        this.repository = repository;
    }

    /** Enregistre un evenement ; un paiement encore transitoire (sans identifiant) est ignore et journalise. */
    @Transactional
    public void record(Payment payment, String eventType, PaymentStatus from, PaymentStatus to,
                       PaymentEventSource source, UUID actorId, Map<String, Object> details) {
        if (payment == null || payment.getId() == null) {
            log.warn("Evenement {} non enregistre : paiement sans identifiant", eventType);
            return;
        }
        repository.save(PaymentEvent.builder()
                .paymentId(payment.getId()).eventType(eventType).fromStatus(from).toStatus(to)
                .source(source).actorId(actorId).details(details == null ? null : new LinkedHashMap<>(details))
                .build());
    }

    @Transactional(readOnly = true)
    public List<PaymentEventResponse> listForPayment(UUID paymentId) {
        return repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream().map(PaymentEventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentEventResponse> listForPayments(List<UUID> paymentIds) {
        if (paymentIds == null || paymentIds.isEmpty()) return List.of();
        return repository.findByPaymentIdInOrderByCreatedAtAsc(paymentIds).stream().map(PaymentEventResponse::from).toList();
    }

    /** Petit constructeur de details sans valeurs nulles (Map.of les refuse). */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }
}
