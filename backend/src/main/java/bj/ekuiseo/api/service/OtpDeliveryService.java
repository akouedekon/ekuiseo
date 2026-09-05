package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.service.mail.MailGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Choix du canal de livraison du code de verification.
 *
 * <p>{@code ekuiseo.otp.channel=email} (defaut) : le code part a l'adresse e-mail du
 * compte ; le numero de telephone reste l'identifiant. Si le compte n'a pas d'adresse,
 * le SMS sert de repli ({@code ekuiseo.otp.sms-fallback=true}) ou la demande est
 * refusee avec un message explicite. {@code ekuiseo.otp.channel=sms} : SMS d'abord.</p>
 *
 * <p>La destination renvoyee au client est masquee (a***@gmail.com, +229 ** ** ** 71)
 * pour ne pas divulguer l'adresse complete a qui connait seulement le numero.</p>
 */
@Service
public class OtpDeliveryService {

    public enum Channel { EMAIL, SMS }

    private final MailGateway mailGateway;
    private final SmsService smsService;
    private final OtpRateLimiter rateLimiter;
    private final Channel preferred;
    private final boolean smsFallback;

    public OtpDeliveryService(MailGateway mailGateway, SmsService smsService, OtpRateLimiter rateLimiter,
                              @Value("${ekuiseo.otp.channel:email}") String channel,
                              @Value("${ekuiseo.otp.sms-fallback:true}") boolean smsFallback) {
        this.mailGateway = mailGateway;
        this.smsService = smsService;
        this.rateLimiter = rateLimiter;
        this.preferred = "sms".equalsIgnoreCase(channel) ? Channel.SMS : Channel.EMAIL;
        this.smsFallback = smsFallback;
    }

    /** Canal qui sera utilise pour ce compte, sans rien envoyer (sert a valider une inscription). */
    public Channel resolveChannel(String email) {
        boolean hasEmail = email != null && !email.isBlank();
        if (preferred == Channel.EMAIL) {
            if (hasEmail) return Channel.EMAIL;
            if (smsFallback) return Channel.SMS;
            throw new BadRequestException("Aucune adresse e-mail n'est associee a ce compte : "
                    + "impossible d'envoyer le code de verification.");
        }
        return Channel.SMS;
    }

    /** Envoie le code par le canal resolu et decrit la destination (masquee) au client. */
    public OtpRequestResponse deliver(String phone, String email, String code) {
        return deliver(phone, email, code, "Votre code Ekuiseo : " + code,
                "Votre code de verification Ekuiseo est : " + code);
    }

    /**
     * Variante avec objet et phrase d introduction propres a l usage (ex : suppression du
     * compte), pour que le destinataire sache ce qu il confirme. Le SMS reste generique.
     */
    public OtpRequestResponse deliver(String phone, String email, String code, String subject, String intro) {
        Channel channel = resolveChannel(email);
        rateLimiter.assertNotRateLimited(phone);
        if (channel == Channel.EMAIL) {
            mailGateway.send(email.trim(), subject,
                    "Bonjour,\n\n" + intro + "\n\n"
                            + "Il expire dans 5 minutes. Ne le partagez avec personne : l'equipe Ekuiseo ne vous "
                            + "le demandera jamais.\n\nSi vous n'etes pas a l'origine de cette demande, ignorez ce message.\n\n"
                            + "Ekuiseo - covoiturage au Benin");
            return new OtpRequestResponse("EMAIL", maskEmail(email.trim()));
        }
        smsService.sendOtp(phone, code);
        return new OtpRequestResponse("SMS", maskPhone(phone));
    }

    static String maskEmail(String email) {
        return Masking.email(email);
    }

    static String maskPhone(String phone) {
        return Masking.phone(phone);
    }
}
