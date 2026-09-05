package bj.ekuiseo.api.common;

import bj.ekuiseo.api.common.exception.BadRequestException;

import java.util.regex.Pattern;

/**
 * Normalisation des numeros de telephone en E.164, source unique cote serveur
 * (inscription, connexion, comptes mobile money, correction admin).
 *
 * <p>Regles :</p>
 * <ul>
 *   <li>Espaces, points, tirets et parentheses sont ignores ; {@code 00} initial devient {@code +}.</li>
 *   <li>Un numero sans indicatif n est accepte que s il est beninois au format
 *       a 10 chiffres commencant par {@code 01} (plan de numerotation du 30/11/2024) :
 *       il recoit {@code +229}.</li>
 *   <li>Un numero beninois a 8 chiffres (ancien format) est refuse avec un message
 *       explicite : ces numeros ne sont plus routes.</li>
 *   <li>Les autres pays (Togo +228, Nigeria +234, ...) suivent E.164 : indicatif
 *       obligatoire, 8 a 15 chiffres au total.</li>
 * </ul>
 * Le front applique la meme regle ({@code toE164} dans lib/validation.ts) : le serveur
 * reste la garde finale, jamais la seule.
 */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s().\\-]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern BENIN_NATIONAL = Pattern.compile("01\\d{8}");
    private static final Pattern E164 = Pattern.compile("[1-9]\\d{7,14}");

    private PhoneNumbers() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Indiquez un numero de telephone");
        }
        String s = SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        }
        boolean international = s.startsWith("+");
        String digits = international ? s.substring(1) : s;
        if (digits.isEmpty() || !DIGITS.matcher(digits).matches()) {
            throw new BadRequestException("Numero de telephone invalide : chiffres attendus, ex. +229 01 97 00 00 00");
        }
        if (!international) {
            if (digits.length() == 10 && digits.startsWith("01")) {
                digits = "229" + digits;
            } else if (digits.length() == 8) {
                throw new BadRequestException(
                        "Depuis 2024, les numeros beninois ont 10 chiffres et commencent par 01 (ex. 01 97 00 00 00)");
            } else {
                throw new BadRequestException("Indiquez l indicatif international, ex. +229 01 97 00 00 00");
            }
        }
        if (digits.startsWith("229")) {
            String national = digits.substring(3);
            if (national.length() == 8) {
                throw new BadRequestException(
                        "Depuis 2024, les numeros beninois ont 10 chiffres et commencent par 01 (ex. +229 01 97 00 00 00)");
            }
            if (!BENIN_NATIONAL.matcher(national).matches()) {
                throw new BadRequestException("Numero beninois invalide : format attendu +229 01 XX XX XX XX");
            }
        } else if (!E164.matcher(digits).matches()) {
            throw new BadRequestException("Numero de telephone invalide (format international attendu, ex. +228 90 00 00 00)");
        }
        return "+" + digits;
    }

    /** Variante sans exception, pour les controles qui ne veulent que savoir si c est plausible. */
    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (BadRequestException ex) {
            return false;
        }
    }
}
