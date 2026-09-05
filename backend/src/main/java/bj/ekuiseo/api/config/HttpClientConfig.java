package bj.ekuiseo.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Delais imposes a TOUS les clients HTTP sortants construits via le
 * {@code RestClient.Builder} auto-configure (Kkiapay, fournisseurs SMS).
 *
 * <p>Sans delai, un agregateur lent bloque le thread appelant indefiniment ; comme
 * ces appels ont lieu dans des transactions JPA, chaque appel bloque retient aussi
 * une connexion du pool Hikari (10) : une lenteur Kkiapay gelerait toute l'API.
 * Un depassement leve {@code ResourceAccessException}, deja convertie en 503 par
 * les passerelles (KkiapayUnavailableException, SmsDeliveryException).</p>
 *
 * <p>Valeurs : connexion 5 s, lecture 15 s (Kkiapay repond en general en moins de
 * 3 s ; 15 s laisse passer une confirmation lente sans immobiliser le pool). Spring
 * Boot 3.4 apportera {@code spring.http.client.*} ; en 3.3 on configure la fabrique.</p>
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClientCustomizer outboundTimeouts(@Value("${ekuiseo.http.connect-timeout-ms:5000}") long connectMs,
                                                 @Value("${ekuiseo.http.read-timeout-ms:15000}") long readMs) {
        return builder -> builder.requestFactory(requestFactory(connectMs, readMs));
    }

    public static ClientHttpRequestFactory requestFactory(long connectMs, long readMs) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return factory;
    }
}
