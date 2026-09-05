package bj.ekuiseo.api.service;

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
import bj.ekuiseo.api.repository.OtpCodeRepository;
import bj.ekuiseo.api.repository.UserRepository;
import bj.ekuiseo.api.security.JwtService;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpDeliveryService otpDelivery;
    private final UserMapper userMapper;
    private final int otpMaxAttempts;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository userRepository, OtpCodeRepository otpCodeRepository,
                        PasswordEncoder passwordEncoder, JwtService jwtService, OtpDeliveryService otpDelivery,
                        UserMapper userMapper,
                        @Value("${ekuiseo.sms.otp.max-attempts:5}") int otpMaxAttempts) {
        this.userRepository = userRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.otpDelivery = otpDelivery;
        this.userMapper = userMapper;
        this.otpMaxAttempts = otpMaxAttempts;
    }

    /**
     * Inscription par OTP (sans mot de passe) : cree le compte avec un mot de passe
     * aleatoire inutilisable, puis envoie le code a l adresse e-mail (obligatoire). Aucun
     * jeton n est remis ici : seule la verification du code (donc l acces a la boite
     * e-mail) ouvre la session.
     * Un numero deja inscrit renvoie 409, comme pour l'inscription classique.
     */
    @Transactional
    public OtpRequestResponse registerWithOtp(OtpRegisterRequest req) {
        String email = req.email().trim();
        Optional<User> existing = userRepository.findByPhone(req.phone());
        if (existing.isPresent()) {
            User pending = existing.get();
            if (pending.isEmailVerified() || pending.isPhoneVerified()) {
                throw new ConflictException("Un compte existe deja avec ce numero de telephone");
            }
            // Compte cree mais jamais verifie : n importe qui a pu saisir ce numero. Plutot que
            // de le bloquer definitivement (squat), on reprend l inscription avec les nouvelles
            // informations ; seule la verification du code fera foi.
            assertActive(pending);
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
                .phone(req.phone())
                .firstName(req.firstName().trim())
                .lastName(req.lastName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode("otp-only-" + UUID.randomUUID()))
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(user);
        return sendCode(user);
    }

    /**
     * Demande de code pour un numero deja inscrit. Le code part a l adresse e-mail du
     * compte (ou par SMS en repli, voir OtpDeliveryService). Un numero inconnu renvoie
     * 404 : l interface propose alors l inscription. Un compte suspendu renvoie 401
     * sans rien envoyer.
     */
    @Transactional
    public OtpRequestResponse requestOtp(OtpRequestRequest req) {
        User user = userRepository.findByPhone(req.phone())
                .orElseThrow(() -> new NotFoundException("Aucun compte associe a ce numero, inscrivez-vous d abord"));
        assertActive(user);
        return sendCode(user);
    }

    /**
     * Genere le code (6 chiffres, hache en base, 5 minutes), choisit le canal et l envoie.
     * Le canal est memorise sur le code pour poser le bon drapeau a la verification.
     * L envoi a lieu apres l ecriture du code : si le fournisseur echoue (503), la
     * transaction est annulee et aucun code fantome ne reste en base.
     */
    private OtpRequestResponse sendCode(User user) {
        OtpDeliveryService.Channel channel = otpDelivery.resolveChannel(user.getEmail());
        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpCode otp = OtpCode.builder()
                .phone(user.getPhone())
                .codeHash(passwordEncoder.encode(code))
                .purpose("LOGIN")
                .channel(channel.name())
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();
        otpCodeRepository.save(otp);
        return otpDelivery.deliver(user.getPhone(), user.getEmail(), code);
    }

    // noRollbackFor : un code faux DOIT laisser en base l increment de attempts (et la
    // consommation du code grille) ; sans cela le rollback annulait le compteur et la
    // limite de 5 essais etait inoperante (constat F536 de l audit).
    @Transactional(noRollbackFor = BadRequestException.class)
    public AuthResponse verifyOtp(OtpVerifyRequest req) {
        OtpCode otp = otpCodeRepository
                .findFirstByPhoneAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(req.phone(), Instant.now())
                .orElseThrow(() -> new BadRequestException("Aucun code valide pour ce numero, redemandez un OTP"));
        if (otp.getAttempts() >= otpMaxAttempts) {
            // Code grille par trop de tentatives incorrectes (regle metier n.8) : on l'invalide
            // definitivement plutot que de laisser expirer normalement, meme s'il reste valide
            // dans sa fenetre de 5 minutes.
            otp.setConsumedAt(Instant.now());
            otpCodeRepository.save(otp);
            throw new BadRequestException("Nombre maximal de tentatives atteint pour ce code, redemandez un OTP");
        }
        if (!passwordEncoder.matches(req.code(), otp.getCodeHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpCodeRepository.save(otp);
            throw new BadRequestException("Code OTP incorrect");
        }
        otp.setConsumedAt(Instant.now());
        otpCodeRepository.save(otp);

        User user = userRepository.findByPhone(req.phone())
                .orElseThrow(() -> new NotFoundException("Aucun compte associe a ce numero, inscrivez-vous d'abord"));
        assertActive(user);
        if ("EMAIL".equals(otp.getChannel())) {
            user.setEmailVerified(true);
        } else {
            user.setPhoneVerified(true);
        }
        userRepository.save(user);
        return tokensFor(user);
    }

    /** Un compte suspendu par la moderation ne peut ouvrir ni prolonger une session, quel que soit le parcours. */
    private void assertActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("Compte suspendu");
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest req) {
        try {
            if (!jwtService.isRefreshToken(req.refreshToken())) {
                throw new UnauthorizedException("Jeton de rafraichissement invalide");
            }
            var userId = jwtService.extractUserId(req.refreshToken());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UnauthorizedException("Utilisateur introuvable"));
            assertActive(user);
            return tokensFor(user);
        } catch (JwtException ex) {
            throw new UnauthorizedException("Jeton de rafraichissement invalide ou expire");
        }
    }

    private AuthResponse tokensFor(User user) {
        String access = jwtService.generateAccessToken(user.getId());
        String refresh = jwtService.generateRefreshToken(user.getId());
        return new AuthResponse(access, refresh, userMapper.toResponse(user));
    }
}
