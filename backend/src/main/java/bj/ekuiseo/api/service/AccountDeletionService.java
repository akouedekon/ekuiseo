package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Suppression de compte a l initiative de l utilisateur (constat F507), en deux temps
 * comme le changement d e-mail : un code a usage unique ({@code DELETE_ACCOUNT}) envoye
 * par le canal habituel, puis l anonymisation ({@link UserService#anonymize}). Les
 * obligations en cours sont verifiees des la demande, pour ne pas envoyer un code qui
 * echouerait ensuite.
 */
@Service
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final OtpCodeService otpCodes;
    private final OtpDeliveryService otpDelivery;

    public AccountDeletionService(UserRepository userRepository, UserService userService,
                                  OtpCodeService otpCodes, OtpDeliveryService otpDelivery) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.otpCodes = otpCodes;
        this.otpDelivery = otpDelivery;
    }

    /** Etape 1 : 409 si une obligation est en cours, sinon envoi du code de confirmation. */
    @Transactional
    public void request(UUID userId) {
        User user = findUser(userId);
        userService.assertCanBeAnonymized(userId);
        OtpDeliveryService.Channel channel = otpDelivery.resolveChannel(user.getEmail());
        String code = otpCodes.issue(user.getPhone(), OtpCodeService.PURPOSE_DELETE_ACCOUNT, channel.name());
        otpDelivery.deliver(user.getPhone(), user.getEmail(), code,
                "Confirmez la suppression de votre compte Ekuiseo : " + code,
                "Vous avez demande la suppression de votre compte Ekuiseo. Pour confirmer, saisissez le code : " + code
                        + "\n\nCette action est definitive : vos donnees personnelles seront effacees.");
    }

    /**
     * Etape 2 : le code consomme, le compte est anonymise. noRollbackFor : un code faux doit
     * laisser en base l increment du compteur d essais (voir OtpCodeService#consume).
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void confirm(UUID userId, String code) {
        User user = findUser(userId);
        otpCodes.consume(user.getPhone(), OtpCodeService.PURPOSE_DELETE_ACCOUNT, code);
        userService.anonymize(userId, userId, "Demande de l utilisateur");
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }
}
