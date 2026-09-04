package bj.ekuiseo.api.service.sms;

/**
 * Abstraction d'envoi de SMS, pour isoler le reste de l'application du fournisseur
 * reel choisi (aucun fournisseur beninois/regional specifique n'est impose par ce
 * squelette : brancher {@link HttpSmsGateway} sur l'API du fournisseur retenu, par
 * exemple un agregateur local ou un service comme Africa's Talking / Twilio).
 */
public interface SmsGateway {
    void send(String phoneE164, String message);
}
