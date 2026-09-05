package bj.ekuiseo.api.service;

import bj.ekuiseo.api.service.sms.SmsGateway;
import org.springframework.stereotype.Service;

/**
 * Orchestration de l'envoi de SMS applicatifs : delegue le transport a
 * {@link SmsGateway} (implementation log ou http, voir SmsConfig).
 *
 * <p>Depuis la livraison des codes par e-mail, le SMS ne sert plus qu'aux
 * notifications critiques et au repli OTP ({@code ekuiseo.otp.sms-fallback}).
 * La limitation de debit des demandes de code vit dans {@link OtpRateLimiter},
 * appliquee par {@link OtpDeliveryService} quel que soit le canal.</p>
 */
@Service
public class SmsService {

    private final SmsGateway smsGateway;

    public SmsService(SmsGateway smsGateway) {
        this.smsGateway = smsGateway;
    }

    /** Envoie un code OTP par SMS (repli quand le compte n'a pas d'adresse e-mail). */
    public void sendOtp(String phone, String code) {
        smsGateway.send(phone, "Ekuiseo : votre code de verification est " + code + ". Il expire dans 5 minutes. "
                + "Ne le partagez avec personne.");
    }

    /** Envoi d'une notification SMS critique (reservation confirmee, trajet annule, rappel...). */
    public void sendCritical(String phone, String message) {
        smsGateway.send(phone, message);
    }
}
