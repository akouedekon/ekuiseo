package bj.ekuiseo.api.service.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Choix explicite de l'implementation {@link SmsGateway} :
 * <ul>
 *   <li>{@code ekuiseo.sms.mode=log} (defaut) : journalise le SMS, aucun envoi ;</li>
 *   <li>{@code ekuiseo.sms.mode=http} : envoi reel, selon {@code ekuiseo.sms.provider} :
 *   {@code twilio}, {@code africastalking}, ou {@code generic} (URL + cle API, corps JSON
 *   {@code {"to","message"}}, voir {@link HttpSmsGateway}).</li>
 * </ul>
 * Une configuration incomplete fait echouer le demarrage avec un message explicite
 * plutot que de laisser les codes OTP partir dans le vide.
 */
@Configuration
public class SmsConfig {

    @Bean
    public SmsGateway smsGateway(RestClient.Builder restClientBuilder,
                                  @Value("${ekuiseo.sms.mode:log}") String mode,
                                  @Value("${ekuiseo.sms.provider:generic}") String provider,
                                  @Value("${ekuiseo.sms.http.url:}") String httpUrl,
                                  @Value("${ekuiseo.sms.http.api-key:${ekuiseo.sms.provider-key:}}") String apiKey,
                                  @Value("${ekuiseo.sms.twilio.account-sid:}") String twilioSid,
                                  @Value("${ekuiseo.sms.twilio.auth-token:}") String twilioToken,
                                  @Value("${ekuiseo.sms.twilio.from:}") String twilioFrom,
                                  @Value("${ekuiseo.sms.africastalking.username:}") String atUsername,
                                  @Value("${ekuiseo.sms.africastalking.api-key:}") String atApiKey,
                                  @Value("${ekuiseo.sms.africastalking.sender-id:}") String atSenderId,
                                  @Value("${ekuiseo.sms.africastalking.sandbox:false}") boolean atSandbox) {
        return switch (mode.toLowerCase()) {
            case "log" -> new LoggingSmsGateway();
            case "http" -> switch (provider.toLowerCase()) {
                case "twilio" -> new TwilioSmsGateway(restClientBuilder, httpUrl, twilioSid, twilioToken, twilioFrom);
                case "africastalking", "africas-talking", "at" ->
                        new AfricasTalkingSmsGateway(restClientBuilder, httpUrl, atUsername, atApiKey, atSenderId, atSandbox);
                case "generic" -> new HttpSmsGateway(httpUrl, apiKey);
                default -> throw new IllegalStateException("ekuiseo.sms.provider invalide : '" + provider
                        + "' (valeurs acceptees : twilio, africastalking, generic)");
            };
            default -> throw new IllegalStateException(
                    "ekuiseo.sms.mode invalide : '" + mode + "' (valeurs acceptees : log, http)");
        };
    }
}
