package bj.ekuiseo.api.service.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Choix explicite de l'implementation {@link SmsGateway} via ekuiseo.sms.mode ("log" par defaut, ou "http"). */
@Configuration
public class SmsConfig {

    @Bean
    public SmsGateway smsGateway(@Value("${ekuiseo.sms.mode:log}") String mode,
                                  @Value("${ekuiseo.sms.http.url:}") String httpUrl,
                                  @Value("${ekuiseo.sms.http.api-key:${ekuiseo.sms.provider-key:}}") String apiKey) {
        return switch (mode.toLowerCase()) {
            case "http" -> new HttpSmsGateway(httpUrl, apiKey);
            case "log" -> new LoggingSmsGateway();
            default -> throw new IllegalStateException(
                    "ekuiseo.sms.mode invalide : '" + mode + "' (valeurs acceptees : log, http)");
        };
    }
}
