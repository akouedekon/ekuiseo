package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Version courante des conditions d utilisation (constat F509) : {@code ekuiseo.terms.version},
 * comparee a {@code users.terms_version} (V16). Un compte qui n a pas accepte la version en
 * vigueur voit {@code termsAcceptanceRequired = true} sur GET /api/v1/me et doit passer par
 * PATCH /api/v1/me/terms ; a l inscription, la version acceptee doit etre celle en vigueur.
 */
@Component
public class TermsPolicy {

    private final String currentVersion;

    public TermsPolicy(@Value("${ekuiseo.terms.version:2026-09}") String currentVersion) {
        if (currentVersion == null || currentVersion.isBlank()) {
            throw new IllegalStateException("ekuiseo.terms.version doit etre renseignee");
        }
        this.currentVersion = currentVersion.trim();
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** Vrai si l utilisateur n a jamais accepte les CGU ou en a accepte une autre version. */
    public boolean acceptanceRequired(User user) {
        return user == null || user.getTermsVersion() == null || !currentVersion.equals(user.getTermsVersion().trim());
    }

    /** 400 si la version transmise n est pas celle en vigueur (formulaire ou application perimes). */
    public void assertCurrent(String version) {
        if (version == null || !currentVersion.equals(version.trim())) {
            throw new BadRequestException("Version des conditions d utilisation inattendue : la version en vigueur est "
                    + currentVersion + ". Rechargez l application puis reessayez.");
        }
    }
}
