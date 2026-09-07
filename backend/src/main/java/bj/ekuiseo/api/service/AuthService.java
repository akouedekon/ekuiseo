package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Masking;
import bj.ekuiseo.api.common.PhoneNumbers;
import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.UnauthorizedException;
import bj.ekuiseo.api.domain.OtpCode;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.UserStatus;
import bj.ekuiseo.api.dto.auth.AuthResponse;
import bj.ekuiseo.api.dto.auth.OtpRegisterRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestRequest;
import bj.ekuiseo.api.dto.auth.OtpRequestResponse;
import bj.ekuiseo.api.dto.auth.OtpVerifyRequest;
import bj.ekuiseo.api.dto.auth.RefreshRequest;
import bj.ekuiseo.api.mapper.UserMapper;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtService;
import bj.ekuiseo.api.security.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Inscription et connexion par code (aucun mot de passe). Le numero de telephone est
 * l identifiant, le code part a l adresse e-mail du compte (SMS en repli si configure).
 *
 * <p>Cycle d un compte : cree en {@code PENDING_VERIFICATION} a l inscription, il
 * devient {@code ACTIVE} a la premiere verification de code ; jamais verifie, il est
 * purge apres 24 h (AuthHousekeepingScheduler) et son numero redevient libre.</p>
 *
 * <p>Phase 3 (constats F541/F544/F148) : chaque evenement d authentification est journalise
 * dans audit_log avec l adresse IP et le User-Agent (OTP_REQUESTED, OTP_VERIFY_FAILED,
 * OTP_VERIFY_SUCCEEDED, TOKEN_REFRESHED), la derniere connexion reussie est conservee sur le
 * compte ({@code users.last_login_at}), et aucun hachage factice de mot de passe n est plus
 * ecrit ({@code password_hash} nul).</p>
 */
@Service
public class AuthService {

    public static final String AUDIT_OTP_REQUESTED = "OTP_REQUESTED";
    public static final String AUDIT_OTP_VERIFY_FAILED = "OTP_VERIFY_FAILED";
    public static final String AUDIT_OTP_VERIFY_SUCCEEDED = "OTP_VERIFY_SUCCEEDED";
    public static final String AUDIT_TOKEN_REFRESHED = "TOKEN_REFRESHED";

    private final UserRepository userRepository;
    private final OtpCodeService otpCodes;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final OtpDeliveryService otpDelivery;
    private final UserMapper userMapper;
    private final TermsPolicy termsPolicy;
    private final AuditService auditService;
    private final RequestContext requestContext;

    /** Message unique pour un numero OU un e-mail deja pris (constat F512 : pas d enumeration des comptes). */
    static final String ALREADY_USED = "Ce numero ou cet e-mail est deja utilise";

    public AuthService(UserRepository userRepository, OtpCodeService otpCodes, JwtService jwtService,
                       RefreshTokenService refreshTokens, OtpDeliveryService otpDelivery, UserMapper userMapper,
                       TermsPolicy termsPolicy, AuditService auditService, RequestContext requestContext) {
        this.userRepository = userRepository;
        this.otpCodes = otpCodes;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.otpDelivery = otpDelivery;
        this.userMapper = userMapper;
        this.termsPolicy = termsPolicy;
        this.auditService = auditService;
        this.requestContext = requestContext;
    }

    /**
     * Inscription : cree le compte en attente de verification (aucun mot de passe, aucun
     * jeton remis) et envoie le code a l adresse e-mail obligatoire.
     * Un numero deja verifie ou un e-mail deja pris renvoie 409 avec un seul et meme message
     * (constat F512) ; un numero jamais verifie est repris avec les nouvelles informations
     * (anti-squat, constat F023). L acceptation des CGU est horodatee avec sa version
     * (constat F509), qui doit etre celle en vigueur.
     */
    @Transactional
    public OtpRequestResponse registerWithOtp(OtpRegisterRequest req) {
        String phone = PhoneNumbers.normalize(req.phone());
        String email = req.email().trim();
        if (!req.acceptTerms()) {
            throw new BadRequestException("Vous devez accepter les conditions d utilisation pour creer un compte");
        }
        termsPolicy.assertCurrent(req.termsVersion());
        Instant acceptedAt = Instant.now();
        Optional<User> existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            User pending = existing.get();
            if (pending.getStatus() != UserStatus.PENDING_VERIFICATION
                    && (pending.isEmailVerified() || pending.isPhoneVerified())) {
                throw new ConflictException(ALREADY_USED);
            }
            assertNotSuspended(pending);
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, pending.getId())) {
                throw new ConflictException(ALREADY_USED);
            }
            pending.setFirstName(req.firstName().trim());
            pending.setLastName(req.lastName().trim());
            pending.setEmail(email);
            pending.setTermsVersion(termsPolicy.currentVersion());
            pending.setTermsAcceptedAt(acceptedAt);
            userRepository.save(pending);
            return sendCode(pending);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException(ALREADY_USED);
        }
        User user = User.builder()
                .phone(phone)
                .firstName(req.firstName().trim())
                .lastName(req.lastName().trim())
                .email(email)
                .status(UserStatus.PENDING_VERIFICATION)
                .termsVersion(termsPolicy.currentVersion())
                .termsAcceptedAt(acceptedAt)
                .build();
        userRepository.save(user);
        return sendCode(user);
    }

    /**
     * Demande de code pour un numero deja inscrit (verifie ou en attente). Un numero
     * inconnu renvoie 404 : l interface propose alors l inscription. Un compte suspendu
     * renvoie 401 sans rien envoyer.
     */
    @Transactional
    public OtpRequestResponse requestOtp(OtpRequestRequest req) {
        String phone = PhoneNumbers.normalize(req.phone());
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new NotFoundException("Aucun compte associe a ce numero, inscrivez-vous d abord"));
        assertNotSuspended(user);
        return sendCode(user);
    }

    /** Choisit le canal, enregistre le code (hache) puis l envoie ; un echec d envoi annule l ecriture. */
    private OtpRequestResponse sendCode(User user) {
        OtpDeliveryService.Channel channel = otpDelivery.resolveChannel(user.getEmail());
        String code = otpCodes.issue(user.getPhone(), OtpCodeService.PURPOSE_LOGIN, channel.name());
        OtpRequestResponse response = otpDelivery.deliver(user.getPhone(), user.getEmail(), code);
        Map<String, Object> details = requestDetails();
        details.put("channel", String.valueOf(response.channel()));
        details.put("destination", String.valueOf(response.destination()));
        auditService.log(user.getId(), AUDIT_OTP_REQUESTED, "user", user.getId(), details);
        return response;
    }

    /**
     * Verification du code : le compte est retrouve d abord (404 si le numero est inconnu),
     * un code faux ou grille est journalise (OTP_VERIFY_FAILED) avant d etre signale (400) ;
     * un succes active un compte en attente, marque le canal verifie, date la connexion et
     * ouvre la session.
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public AuthResponse verifyOtp(OtpVerifyRequest req) {
        String phone = PhoneNumbers.normalize(req.phone());
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new NotFoundException("Aucun compte associe a ce numero, inscrivez-vous d abord"));
        assertNotSuspended(user);
        OtpCode otp;
        try {
            otp = otpCodes.consume(phone, OtpCodeService.PURPOSE_LOGIN, req.code());
        } catch (BadRequestException ex) {
            Map<String, Object> details = requestDetails();
            details.put("reason", ex.getMessage());
            details.put("phone", Masking.phone(phone));
            auditService.log(user.getId(), AUDIT_OTP_VERIFY_FAILED, "user", user.getId(), details);
            throw ex;
        }
        if ("EMAIL".equals(otp.getChannel())) {
            user.setEmailVerified(true);
        } else {
            user.setPhoneVerified(true);
        }
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        Map<String, Object> details = requestDetails();
        details.put("channel", String.valueOf(otp.getChannel()));
        auditService.log(user.getId(), AUDIT_OTP_VERIFY_SUCCEEDED, "user", user.getId(), details);
        return tokensFor(user);
    }

    /** Rotation du jeton de rafraichissement (voir RefreshTokenService) ; 401 pour un compte suspendu. */
    @Transactional
    public AuthResponse refresh(RefreshRequest req) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(req.refreshToken());
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException("Utilisateur introuvable"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            refreshTokens.revokeAll(user.getId());
            throw new UnauthorizedException("Compte suspendu");
        }
        auditService.log(user.getId(), AUDIT_TOKEN_REFRESHED, "user", user.getId(), requestDetails());
        return new AuthResponse(jwtService.generateAccessToken(user.getId()), rotation.refreshToken(),
                userMapper.toResponse(user));
    }

    /** Deconnexion : revoque la chaine du jeton presente. Toujours silencieux (jeton absent ou deja invalide). */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revoke(refreshToken);
        }
    }

    /** Un compte suspendu par la moderation, ou supprime, ne peut ni recevoir de code ni ouvrir de session. */
    private void assertNotSuspended(User user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UnauthorizedException("Compte suspendu");
        }
        if (user.getStatus() == UserStatus.DELETED) {
            throw new UnauthorizedException("Compte supprime");
        }
    }

    /** IP et User-Agent de la requete en cours (vides hors requete HTTP), base de chaque entree d audit. */
    private Map<String, Object> requestDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        String ip = requestContext.clientIp();
        String userAgent = requestContext.userAgent();
        details.put("ip", ip == null ? "" : ip);
        details.put("userAgent", userAgent == null ? "" : userAgent);
        return details;
    }

    private AuthResponse tokensFor(User user) {
        String access = jwtService.generateAccessToken(user.getId());
        String refresh = refreshTokens.issue(user.getId());
        return new AuthResponse(access, refresh, userMapper.toResponse(user));
    }
}
