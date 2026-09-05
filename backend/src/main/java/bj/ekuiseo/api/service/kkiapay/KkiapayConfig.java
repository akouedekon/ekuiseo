package bj.ekuiseo.api.service.kkiapay;

import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Choix explicite de l'implementation de {@link KkiapayGateway} via
 * ekuiseo.kkiapay.mode ("stub" par defaut, ou "http"). Un choix explicite (plutot
 * qu'une deduction automatique a partir de la presence des cles) evite qu'un
 * environnement mal configure ne parte silencieusement en mode stub, ou inversement.
 */
@Configuration
public class KkiapayConfig {

    @Bean
    public KkiapayGateway kkiapayGateway(
            RestClient.Builder restClientBuilder,
            @Value("${ekuiseo.kkiapay.mode:stub}") String mode,
            @Value("${ekuiseo.kkiapay.public-key:}") String publicKey,
            @Value("${ekuiseo.kkiapay.private-key:}") String privateKey,
            @Value("${ekuiseo.kkiapay.secret:}") String secret,
            @Value("${ekuiseo.kkiapay.sandbox:true}") boolean sandbox) {
        return switch (mode.toLowerCase()) {
            case "http" -> new KkiapayHttpGateway(restClientBuilder, publicKey, privateKey, secret, sandbox);
            case "stub" -> new KkiapayStubGateway();
            default -> throw new IllegalStateException(
                    "ekuiseo.kkiapay.mode invalide : '" + mode + "' (valeurs acceptees : stub, http)");
        };
    }
}
