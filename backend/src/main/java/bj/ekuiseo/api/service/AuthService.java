package bj.ekuiseo.api.service;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Inscription et connexion par code (aucun mot de passe). Le numero de telephone est
 * l identifiant, le code part a l adresse e-mail du compte (SMS en repli si configure).
 *
 * <p>Cycle d un compte : cree en {@code PENDING_VERIFICATION} a l inscription, il
 * devient {@code ACTIVE} a la premiere verification de code ; jamais verifie, il est
 * purge apres 24 h (AuthHousekeepingScheduler) et son numero redevient libre.</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OtpCodeService otpCodes;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final OtpDeliveryService otpDelivery;
    private final UserMapper userMapper;

    public AuthService(UserRepository userRepository, OtpCodeService otpCodes, PasswordEncoder passwordEncoder,
                       JwtService jwtService, RefreshTokenService refreshTokens, OtpDeliveryService otpDelivery,
                       UserMapper userMapper) {
        this.userRepository = userRepository;
        this.otpCodes = otpCodes;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.otpDelivery = otpDelivery;
        this.userMapper = userMapper;
    }

    /**
     * Inscription : cree le compte en attente de verification (mot de passe aleatoire
     * inutilisable, aucun jeton remis) et envoie le code a l adresse e-mail obligatoire.
     * Un numero deja verifie renvoie 409 ; un numero jamais verifie est repris avec les
     * nouvelles informations (anti-squat, constat F023).
     */
    @Transactional
    public OtpRequestResponse registerWithOtp(OtpRegisterRequest req) {
        String phone = PhoneNumbers.normalize(req.phone());
        String email = req.email().trim();
        Optional<User> existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            User pending = existing.get();
            if (pending.getStatus() != UserStatus.PENDING_VERIFICATION
                    && (pending.isEmailVerified() || pending.isPhoneVerified())) {
                throw new ConflictException("Un compte existe deja avec ce numero de telephone");
            }
            assertNotSuspended(pending);
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, pending.getId())) {
                throw new ConflictException("Un compte existe deja avec cette adresse e-mail");
            }
            pending.setFirstName(req.firstName().trim());
            pending.setLastName(req.lastName().trim());
            pending.setEmail(email);
            userRepository.save(pending);
            return sendCode(pending);
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Un compte existe deja avec cette adresse e-mail");
        }
        User user = User.builder()
                .phone(phone)
                .firstName(req.firstName().trim())
                .lastName(req.lastName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode("otp-only-" + UUID.randomUUID()))
                .status(UserStatus.PENDING_VERIFICATION)
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
        return otpDelivery.deliver(user.getPhone(), user.getEmail(), code);
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public AuthResponse verifyOtp(OtpVerifyRequest req) {
        String phone = PhoneNumbers.normalize(req.phone());
        OtpCode otp = otpCodes.consume(phone, OtpCodeService.PURPOSE_LOGIN, req.code());

        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new NotFoundException("Aucun compte associe a ce numero, inscrivez-vous d abord"));
        assertNotSuspended(user);
        if ("EMAIL".equals(otp.getChannel())) {
            user.setEmailVerified(true);
        } else {
            user.setPhoneVerified(true);
        }
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);
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

    /** Un compte suspendu par la moderation ne peut ni recevoir de code ni ouvrir de session. */
    private void assertNotSuspended(User user) {
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UnauthorizedException("Compte suspendu");
        }
    }

    private AuthResponse tokensFor(User user) {
        String access = jwtService.generateAccessToken(user.getId());
        String refresh = refreshTokens.issue(user.getId());
        return new AuthResponse(access, refresh, userMapper.toResponse(user));
    }
}
