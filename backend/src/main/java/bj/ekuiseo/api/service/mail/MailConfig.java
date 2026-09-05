package bj.ekuiseo.api.service.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Choix explicite de l'implementation {@link MailGateway} : {@code ekuiseo.mail.mode=log}
 * (defaut) ou {@code smtp}. Le {@link JavaMailSender} n'existe que si
 * {@code spring.mail.host} est renseigne (auto-configuration Spring Boot) ; en mode smtp
 * son absence fait echouer le demarrage avec un message explicite.
 */
@Configuration
public class MailConfig {

    @Bean
    public MailGateway mailGateway(@Value("${ekuiseo.mail.mode:log}") String mode,
                                    @Value("${ekuiseo.mail.from:}") String from,
                                    @Value("${spring.mail.host:}") String host,
                                    ObjectProvider<JavaMailSender> mailSender) {
        return switch (mode.toLowerCase()) {
            case "log" -> new LoggingMailGateway();
            // Spring Boot cree un JavaMailSender des que spring.mail.host est defini, meme vide :
            // on verifie explicitement l hote pour echouer au demarrage plutot qu au premier envoi.
            case "smtp" -> new SmtpMailGateway(host == null || host.isBlank() ? null : mailSender.getIfAvailable(), from);
            default -> throw new IllegalStateException(
                    "ekuiseo.mail.mode invalide : '" + mode + "' (valeurs acceptees : log, smtp)");
        };
    }
}
