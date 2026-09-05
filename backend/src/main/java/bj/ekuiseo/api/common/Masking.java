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

    /** {@code lakouedekon@gmail.com} devient {@code la***@gmail.com}. */
    public static String email(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String visible = local.substring(0, Math.min(2, local.length()));
        return visible + "***" + domain;
    }

    /** {@code +2290196870371} devient {@code ************71}. */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "*".repeat(phone.length() - 2) + phone.substring(phone.length() - 2);
    }

    /** Remplace toute suite de 4 a 8 chiffres (un code de connexion) par des etoiles. */
    public static String codes(String text) {
        if (text == null) return null;
        return text.replaceAll("\\b\\d{4,8}\\b", "******");
    }
}
