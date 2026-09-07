package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Coherence operateur / numero d un compte mobile money beninois (constat F607).
 *
 * <p>Plan de numerotation du 30/11/2024 : tout numero mobile est {@code 01 XX XX XX XX}, les
 * deux chiffres apres {@code 01} identifient l operateur. Les prefixes sont configurables
 * ({@code ekuiseo.momo.prefixes.<OPERATEUR>}, liste separee par des virgules, espaces
 * ignores) pour suivre les attributions de l ARCEP sans redeploiement du code :</p>
 * <ul>
 *   <li>MTN : 01 51, 52, 53, 54, 56, 57, 59, 61, 62, 66, 67, 69, 90, 91, 96, 97 ;</li>
 *   <li>Moov : 01 55, 58, 60, 63, 64, 65, 68, 94, 95, 98, 99 ;</li>
 *   <li>Celtiis : 01 40 a 01 50.</li>
 * </ul>
 * Seuls les numeros beninois (+229) sont controles : un compte etranger (Togo, Nigeria)
 * est accepte tel quel, la plateforme ne connaissant pas ces plans de numerotation.
 */
@Component
public class MobileMoneyPrefixes {

    static final String DEFAULT_MTN = "0151,0152,0153,0154,0156,0157,0159,0161,0162,0166,0167,0169,0190,0191,0196,0197";
    static final String DEFAULT_MOOV = "0155,0158,0160,0163,0164,0165,0168,0194,0195,0198,0199";
    static final String DEFAULT_CELTIIS = "0140,0141,0142,0143,0144,0145,0146,0147,0148,0149,0150";

    private final Map<MobileMoneyOperator, List<String>> prefixes = new EnumMap<>(MobileMoneyOperator.class);

    public MobileMoneyPrefixes(@Value("${ekuiseo.momo.prefixes.MTN_MOMO:" + DEFAULT_MTN + "}") String mtn,
                               @Value("${ekuiseo.momo.prefixes.MOOV_MONEY:" + DEFAULT_MOOV + "}") String moov,
                               @Value("${ekuiseo.momo.prefixes.CELTIIS_CASH:" + DEFAULT_CELTIIS + "}") String celtiis) {
        prefixes.put(MobileMoneyOperator.MTN_MOMO, parse(mtn));
        prefixes.put(MobileMoneyOperator.MOOV_MONEY, parse(moov));
        prefixes.put(MobileMoneyOperator.CELTIIS_CASH, parse(celtiis));
    }

    /** Valeurs par defaut, pour les tests et les composants sans configuration. */
    public static MobileMoneyPrefixes defaults() {
        return new MobileMoneyPrefixes(DEFAULT_MTN, DEFAULT_MOOV, DEFAULT_CELTIIS);
    }

    private static List<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
                .map(s -> s.replaceAll("\\s", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Operateur deduit d un numero E.164 beninois, ou null si etranger / prefixe inconnu. */
    public MobileMoneyOperator detect(String e164) {
        String national = beninNational(e164);
        if (national == null) return null;
        for (Map.Entry<MobileMoneyOperator, List<String>> entry : prefixes.entrySet()) {
            for (String prefix : entry.getValue()) {
                if (national.startsWith(prefix)) return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 400 explicite si le numero beninois n appartient pas a l operateur declare. Un prefixe
     * inconnu de toutes les listes est refuse aussi : mieux vaut un message clair qu un
     * reversement vers un numero qui n existe pas.
     */
    public void assertConsistent(MobileMoneyOperator operator, String e164) {
        String national = beninNational(e164);
        if (national == null || operator == null) return;
        MobileMoneyOperator detected = detect(e164);
        if (detected == operator) return;
        if (detected == null) {
            throw new BadRequestException("Le prefixe " + national.substring(0, Math.min(4, national.length()))
                    + " ne correspond a aucun operateur mobile money connu (MTN, Moov, Celtiis) : verifiez le numero");
        }
        throw new BadRequestException("Ce numero (" + national.substring(0, 2) + " " + national.substring(2, 4)
                + ") est un numero " + label(detected) + ", pas " + label(operator)
                + " : choisissez l operateur correspondant");
    }

    private static String beninNational(String e164) {
        if (e164 == null || !e164.startsWith("+229")) return null;
        return e164.substring(4);
    }

    static String label(MobileMoneyOperator operator) {
        return switch (operator) {
            case MTN_MOMO -> "MTN MoMo";
            case MOOV_MONEY -> "Moov Money";
            case CELTIIS_CASH -> "Celtiis Cash";
        };
    }
}
