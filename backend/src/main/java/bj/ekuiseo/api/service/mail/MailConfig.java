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
 *
 * <p>Garde-fou de production : les codes de connexion partent par e-mail. Un serveur
 * qui encaisse de vrais paiements ({@code ekuiseo.kkiapay.mode=http}) avec un canal
 * e-mail journalise n'envoie aucun code a personne et laisse les codes dans les logs :
 * on refuse de demarrer, sauf derogation explicite
 * ({@code ekuiseo.gateways.allow-log-with-real-payments=true}, reservee a une recette).</p>
 */
@Configuration
public class MailConfig {

    @Bean
    public MailGateway mailGateway(@Value("${ekuiseo.mail.mode:log}") String mode,
                                    @Value("${ekuiseo.mail.from:}") String from,
                                    @Value("${spring.mail.host:}") String host,
                                    @Value("${ekuiseo.otp.log-plain-codes:false}") boolean logPlainCodes,
                                    @Value("${ekuiseo.kkiapay.mode:stub}") String kkiapayMode,
                                    @Value("${ekuiseo.gateways.allow-log-with-real-payments:false}") boolean allowLogWithRealPayments,
                                    ObjectProvider<JavaMailSender> mailSender) {
        return switch (mode.toLowerCase()) {
            case "log" -> {
                if ("http".equalsIgnoreCase(kkiapayMode) && !allowLogWithRealPayments) {
                    throw new IllegalStateException("ekuiseo.mail.mode=log est refuse quand ekuiseo.kkiapay.mode=http "
                            + "(paiements reels) : les codes de connexion n'arriveraient a personne. Renseignez "
                            + "MAIL_MODE=smtp et le relais SMTP, ou posez EKUISEO_ALLOW_LOG_GATEWAYS=true pour une recette.");
                }
                yield new LoggingMailGateway(logPlainCodes);
            }
            // Spring Boot cree un JavaMailSender des que spring.mail.host est defini, meme vide :
            // on verifie explicitement l hote pour echouer au demarrage plutot qu au premier envoi.
            case "smtp" -> new SmtpMailGateway(host == null || host.isBlank() ? null : mailSender.getIfAvailable(), from);
            default -> throw new IllegalStateException(
                    "ekuiseo.mail.mode invalide : '" + mode + "' (valeurs acceptees : log, smtp)");
        };
    }
}
