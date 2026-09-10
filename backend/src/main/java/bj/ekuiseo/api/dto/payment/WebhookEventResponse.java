package bj.ekuiseo.api.dto.payment;

import bj.ekuiseo.api.domain.PaymentWebhookEvent;

import java.time.Instant;
import java.util.UUID;

/** Webhook recu (contrat A.5), GET /api/v1/admin/payments/webhooks. Le corps n est pas expose (donnees du fournisseur). */
public record WebhookEventResponse(
        UUID id,
        String provider,
        String providerTxId,
        boolean signatureValid,
        Instant receivedAt,
        Instant processedAt,
        String outcome,
        String error
) {
    public static WebhookEventResponse from(PaymentWebhookEvent e) {
        return new WebhookEventResponse(e.getId(), e.getProvider(), e.getProviderTxId(), e.isSignatureValid(),
                e.getReceivedAt(), e.getProcessedAt(), e.getOutcome().name(), e.getError());
    }
}
