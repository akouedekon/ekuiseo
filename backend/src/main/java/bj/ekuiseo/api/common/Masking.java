package bj.ekuiseo.api.common;

/**
 * Masquage des identifiants personnels avant journalisation ou renvoi au client.
 * Un numero ou une adresse complete dans les logs est une donnee personnelle
 * (docs/CONFORMITE.md) et, associee a un code de connexion, une faille : on ne
 * journalise jamais plus que ce qui permet de diagnostiquer.
 */
public final class Masking {

    private Masking() {
    }

    /**
     * {@code lakouedekon@gmail.com} devient {@code l***@g***.com} (constat F512) : un seul
     * caractere de la partie locale et le domaine tronque a sa premiere lettre et son
     * extension, pour que qui ne connait que le numero ne puisse pas reconstituer l adresse.
     */
    public static String email(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String domainMasked = dot > 0
                ? domain.charAt(0) + "***" + domain.substring(dot)
                : domain.charAt(0) + "***";
        return local.charAt(0) + "***@" + domainMasked;
    }

    /** {@code +2290196870371} devient {@code ************71}. */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "*".repeat(phone.length() - 2) + phone.substring(phone.length() - 2);
    }

    /** Numero de piece d identite : seuls les deux derniers caracteres restent lisibles (meme regle que {@link #phone}). */
    public static String documentNumber(String number) {
        return phone(number);
    }

    /**
     * Initiale du nom de famille suivie d un point (« Aholou » -> « A. »), montree aux
     * appelants anonymes a la place du nom complet (constat F519) ; null ou vide reste tel quel.
     */
    public static String lastNameInitial(String lastName) {
        if (lastName == null) return null;
        String trimmed = lastName.trim();
        if (trimmed.isEmpty()) return trimmed;
        return trimmed.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + ".";
    }

    /** Remplace toute suite de 4 a 8 chiffres (un code de connexion) par des etoiles. */
    public static String codes(String text) {
        if (text == null) return null;
        return text.replaceAll("\\b\\d{4,8}\\b", "******");
    }
}
