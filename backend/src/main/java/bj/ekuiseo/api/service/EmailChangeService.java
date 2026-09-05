package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.user.UserResponse;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.service.mail.MailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Changement d adresse e-mail en deux temps (constats F013/F538/F104) : l e-mail est
 * le canal des codes de connexion, il ne se change donc qu apres preuve d acces a la
 * nouvelle adresse. L ancienne adresse est prevenue, l operation journalisee.
 */
@Service
public class EmailChangeService {

    private static final Logger log = LoggerFactory.getLogger(EmailChangeService.class);

    private final UserRepository userRepository;
    private final OtpCodeService otpCodes;
    private final OtpRateLimiter rateLimiter;
    private final MailGateway mailGateway;
    private final AuditService auditService;
    private final UserMapper userMapper;

    public EmailChangeService(UserRepository userRepository, OtpCodeService otpCodes, OtpRateLimiter rateLimiter,
                              MailGateway mailGateway, AuditService auditService, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.otpCodes = otpCodes;
        this.rateLimiter = rateLimiter;
        this.mailGateway = mailGateway;
        this.auditService = auditService;
        this.userMapper = userMapper;
    }

    /** Etape 1 : enregistre l adresse en attente et envoie un code a cette nouvelle adresse. */
    @Transactional
    public OtpRequestResponse request(UUID userId, String rawEmail) {
        User user = findUser(userId);
        String email = rawEmail == null ? "" : rawEmail.trim();
        if (email.isEmpty()) {
            throw new BadRequestException("Indiquez la nouvelle adresse e-mail");
        }
        if (user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)) {
            throw new BadRequestException("C est deja l adresse de votre compte");
        }
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, userId)) {
            throw new ConflictException("Cette adresse e-mail est deja utilisee par un autre compte");
        }
        rateLimiter.assertNotRateLimited("email-change:" + user.getPhone());
        user.setPendingEmail(email);
        userRepository.save(user);
        String code = otpCodes.issue(user.getPhone(), OtpCodeService.PURPOSE_CHANGE_EMAIL, "EMAIL");
        mailGateway.send(email, "Confirmez votre nouvelle adresse Ekuiseo : " + code,
                "Bonjour " + user.getFirstName() + ",\n\nPour utiliser cette adresse comme adresse de connexion Ekuiseo, "
                        + "saisissez le code : " + code + "\n\nIl expire dans 5 minutes. Si vous n etes pas a l origine "
                        + "de cette demande, ignorez ce message : rien ne changera.\n\nEkuiseo - covoiturage au Benin");
        return new OtpRequestResponse("EMAIL", Masking.email(email));
    }

    /** Etape 2 : le code recu sur la nouvelle adresse bascule le compte et previent l ancienne. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public UserResponse confirm(UUID userId, String code) {
        User user = findUser(userId);
        String pending = user.getPendingEmail();
        if (pending == null || pending.isBlank()) {
            throw new BadRequestException("Aucun changement d adresse en attente : demandez d abord un code");
        }
        otpCodes.consume(user.getPhone(), OtpCodeService.PURPOSE_CHANGE_EMAIL, code);
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(pending, userId)) {
            user.setPendingEmail(null);
            userRepository.save(user);
            throw new ConflictException("Cette adresse e-mail vient d etre prise par un autre compte");
        }
        String previous = user.getEmail();
        user.setEmail(pending);
        user.setPendingEmail(null);
        user.setEmailVerified(true);
        user = userRepository.save(user);
        auditService.log(userId, "USER_EMAIL_CHANGED", "user", userId,
                Map.of("previous", previous == null ? "" : previous, "next", pending));
        if (previous != null && !previous.isBlank()) {
            try {
                mailGateway.send(previous, "Votre adresse de connexion Ekuiseo a change",
                        "Bonjour " + user.getFirstName() + ",\n\nL adresse e-mail de votre compte Ekuiseo vient d etre "
                                + "remplacee par " + Masking.email(pending) + ". Les prochains codes de connexion partiront "
                                + "a cette nouvelle adresse.\n\nSi vous n etes pas a l origine de ce changement, repondez "
                                + "a ce message sans attendre pour que nous bloquions le compte.\n\nEkuiseo - covoiturage au Benin");
            } catch (RuntimeException ex) {
                // L avis a l ancienne adresse est une courtoisie : son echec ne doit pas annuler le changement.
                log.warn("Avis de changement d adresse non envoye a {}", Masking.email(previous), ex);
            }
        }
        return userMapper.toResponse(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }
}
