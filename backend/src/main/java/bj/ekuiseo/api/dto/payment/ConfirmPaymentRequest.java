package bj.ekuiseo.api.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de POST /api/v1/payments/{paymentId}/confirm : l'identifiant de transaction
 * que le widget Kkiapay a remis au frontend dans son evenement "success". Le serveur
 * ne fait pas confiance a cet evenement : il reverifie la transaction aupres de
 * l'API Kkiapay avant de toucher a la reservation (voir PaymentService#confirmFromWidget).
 */
public record ConfirmPaymentRequest(
        @NotBlank @Size(max = 120) String transactionId
) {
}
