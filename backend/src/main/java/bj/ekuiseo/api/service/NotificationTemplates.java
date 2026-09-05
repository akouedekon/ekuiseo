package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.Tz;
import bj.ekuiseo.api.domain.enums.NotificationType;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Textes en francais des notifications sortantes (e-mail et SMS), produits par type a
 * partir du payload enregistre en base pour la notification in-app (constat F107).
 *
 * <p>Les cles du payload sont facultatives : un texte reste lisible meme si l appelant
 * n a renseigne que les identifiants. Les montants sont en FCFA entiers (regle metier
 * n.1) ; les instants ISO du payload sont affiches a l heure du Benin.</p>
 */
public final class NotificationTemplates {

    /** Sujet et corps de l e-mail, et texte court du SMS (utilise seulement pour les notifications critiques). */
    public record Rendered(String subject, String body, String sms) {
    }

    private static final String SIGNATURE = "\n\nEkuiseo - covoiturage au Benin";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("EEEE d MMMM yyyy 'a' HH:mm", Locale.FRENCH).withZone(Tz.BENIN);

    private NotificationTemplates() {
    }

    public static Rendered render(NotificationType type, Map<String, Object> payload) {
        Map<String, Object> p = payload == null ? Map.of() : payload;
        String route = str(p, "route");
        String when = instant(p, "departureAt");
        String tripLine = tripLine(route, when);
        switch (type) {
            case PAYMENT_SUCCEEDED: {
                String reference = str(p, "reference");
                StringBuilder body = new StringBuilder("Nous avons bien recu votre paiement");
                if (has(p, "amountFcfa")) body.append(" de ").append(money(p, "amountFcfa"));
                body.append(".").append(tripLine.isEmpty() ? "" : "\n\nTrajet : " + tripLine + ".");
                if (has(p, "totalFcfa")) body.append("\nPrix total de la reservation : ").append(money(p, "totalFcfa")).append(".");
                if (has(p, "balanceDueOnBoardFcfa")) {
                    long balance = number(p, "balanceDueOnBoardFcfa");
                    body.append(balance > 0
                            ? "\nSolde a regler en especes au conducteur a bord : " + money(p, "balanceDueOnBoardFcfa") + "."
                            : "\nRien a regler a bord : la reservation est entierement payee.");
                }
                if (!reference.isEmpty()) body.append("\nReference de paiement : ").append(reference).append(".");
                body.append("\n\nVotre reservation est confirmee. Retrouvez-la dans l'application, rubrique Mes reservations.");
                return finish("Recu de paiement : votre reservation est confirmee", body.toString(),
                        "Ekuiseo : paiement recu, votre reservation est confirmee."
                                + (has(p, "balanceDueOnBoardFcfa") && number(p, "balanceDueOnBoardFcfa") > 0
                                ? " Solde a bord : " + money(p, "balanceDueOnBoardFcfa") + "." : ""));
            }
            case PAYMENT_FAILED:
                return finish("Votre paiement n'a pas abouti",
                        "Le paiement de votre acompte n'a pas abouti. Votre reservation reste en attente et sera "
                                + "liberee automatiquement si elle n'est pas reglee dans les 20 minutes suivant sa creation."
                                + "\n\nVous pouvez reessayer depuis l'application.",
                        "Ekuiseo : votre paiement n'a pas abouti, reessayez depuis l'application.");
            case BOOKING_CONFIRMED: {
                String passenger = str(p, "passengerName");
                String seats = has(p, "seats") ? number(p, "seats") + " place(s)" : "";
                String body = "Nouvelle reservation confirmee sur votre trajet"
                        + (tripLine.isEmpty() ? "" : " " + tripLine) + "."
                        + (passenger.isEmpty() ? "" : "\nPassager : " + passenger + ".")
                        + (seats.isEmpty() ? "" : "\nPlaces reservees : " + seats + ".")
                        + "\n\nVous pouvez echanger avec votre passager depuis la messagerie de l'application.";
                return finish("Nouvelle reservation sur votre trajet", body,
                        "Ekuiseo : nouvelle reservation confirmee sur votre trajet" + (route.isEmpty() ? "" : " " + route) + ".");
            }
            case BOOKING_CANCELLED: {
                String by = str(p, "cancelledBy");
                String body;
                if ("PASSENGER".equals(by)) {
                    body = "Un passager a annule sa reservation" + (has(p, "seats") ? " (" + number(p, "seats") + " place(s))" : "")
                            + " sur votre trajet" + (tripLine.isEmpty() ? "" : " " + tripLine) + ". Les places sont de nouveau disponibles.";
                } else if ("DRIVER".equals(by)) {
                    body = "Votre trajet" + (tripLine.isEmpty() ? "" : " " + tripLine) + " a ete annule par le conducteur."
                            + refundLine(p);
                } else if ("PLATFORM".equals(by)) {
                    body = "Votre reservation" + (tripLine.isEmpty() ? "" : " sur le trajet " + tripLine)
                            + " a ete annulee par la plateforme." + refundLine(p);
                } else {
                    body = "Une reservation" + (tripLine.isEmpty() ? "" : " sur le trajet " + tripLine) + " a ete annulee." + refundLine(p);
                }
                return finish("Reservation annulee", body, "Ekuiseo : " + body.replace("\n", " "));
            }
            case TRIP_REMINDER:
                return finish("Rappel : votre trajet part demain",
                        "Votre trajet" + (tripLine.isEmpty() ? "" : " " + tripLine) + " part demain."
                                + "\n\nPensez a prevoir le solde en especes si votre reservation en prevoit un, et a prevenir "
                                + "le conducteur en cas d'empechement : une annulation tardive retient l'acompte.\n\nBon voyage !",
                        "Ekuiseo : rappel, votre trajet" + (route.isEmpty() ? "" : " " + route) + " part demain"
                                + (when.isEmpty() ? "" : " (" + when + ")") + ". Bon voyage !");
            case TRIP_UPDATED: {
                String previous = instant(p, "previousDepartureAt");
                String freeUntil = instant(p, "freeCancellationUntil");
                String body = "Le conducteur a modifie votre trajet" + (route.isEmpty() ? "" : " " + route) + "."
                        + (previous.isEmpty() || when.isEmpty() ? "" : "\nDepart initialement prevu le " + previous + ", desormais le " + when + ".")
                        + "\n\nSi ce nouvel horaire ne vous convient pas, vous pouvez annuler sans frais"
                        + (freeUntil.isEmpty() ? " pendant 24 heures." : " jusqu'au " + freeUntil + ".");
                return finish("Votre trajet a ete modifie", body,
                        "Ekuiseo : le depart de votre trajet" + (route.isEmpty() ? "" : " " + route) + " est deplace"
                                + (when.isEmpty() ? "" : " au " + when) + ". Annulation sans frais pendant 24 h.");
            }
            case BOOKING_NO_SHOW:
                return finish("Absence constatee au depart",
                        "Le conducteur a signale votre absence au depart du trajet"
                                + (tripLine.isEmpty() ? "" : " " + tripLine) + "."
                                + (has(p, "retainedAmountFcfa") ? "\nL'acompte de " + money(p, "retainedAmountFcfa") + " est retenu." : "")
                                + "\n\nSi vous contestez cette absence, signalez-le depuis l'application : la moderation examinera la situation.",
                        "Ekuiseo : le conducteur a signale votre absence au depart ; l'acompte est retenu.");
            case NEW_MESSAGE:
                return finish("Nouveau message",
                        "Vous avez recu un nouveau message concernant une reservation. Repondez depuis la messagerie de l'application.",
                        "Ekuiseo : nouveau message recu, consultez l'application.");
            case NEW_REVIEW:
                return finish("Vous avez recu un avis",
                        "Un membre vient de vous laisser un avis" + (has(p, "rating") ? " (" + number(p, "rating") + "/5)" : "")
                                + ". Retrouvez-le sur votre profil.",
                        "Ekuiseo : vous avez recu un nouvel avis.");
            case SEARCH_ALERT_MATCH:
                return finish("Un trajet correspond a votre alerte",
                        "Un nouveau trajet" + (tripLine.isEmpty() ? "" : " " + tripLine) + " vient d'etre publie et correspond "
                                + "a votre alerte de recherche. Les places partent vite : reservez depuis l'application.",
                        "Ekuiseo : un trajet" + (route.isEmpty() ? "" : " " + route) + " correspond a votre alerte, reservez vite.");
            case SUBSCRIPTION_ACTIVATED:
                return finish("Votre abonnement conducteur est actif",
                        "Votre abonnement conducteur est actif pour 30 jours : aucune commission n'est prelevee sur vos trajets "
                                + "pendant cette periode.",
                        "Ekuiseo : votre abonnement conducteur est actif, vous ne payez plus de commission ce mois-ci.");
            case REPORT_RECEIVED:
                return finish("Signalement recu",
                        "Nous avons bien recu votre signalement. La moderation l'examinera et vous tiendra informe de son issue.",
                        "Ekuiseo : votre signalement a bien ete recu.");
            case PAYMENT_REFUND_PENDING: {
                boolean manual = Boolean.TRUE.equals(p.get("manual"));
                String body = "Un remboursement" + (has(p, "amountFcfa") ? " de " + money(p, "amountFcfa") : "") + " est en cours"
                        + (manual ? " : il sera traite manuellement par notre equipe sous quelques jours ouvres."
                        : " aupres de votre operateur mobile money. Il apparait generalement sous 48 heures.")
                        + "\n\nVous recevrez une confirmation des qu'il sera effectif.";
                return finish("Remboursement en cours", body, "Ekuiseo : votre remboursement est en cours.");
            }
            case PAYMENT_REFUNDED:
                return finish("Remboursement effectue",
                        "Votre remboursement" + (has(p, "amountFcfa") ? " de " + money(p, "amountFcfa") : "")
                                + " a ete effectue sur votre compte mobile money.",
                        "Ekuiseo : votre remboursement" + (has(p, "amountFcfa") ? " de " + money(p, "amountFcfa") : "") + " a ete effectue.");
            case PAYOUT_ACCOUNT_MISSING:
                return finish("Reversement en attente : compte mobile money manquant",
                        "Un reversement" + (has(p, "amountFcfa") ? " de " + money(p, "amountFcfa") : "") + " vous est du, mais "
                                + "aucun compte mobile money verifie n'est associe a votre profil. Ajoutez-en un depuis "
                                + "Mon compte > Mobile money : il sera inclus dans le prochain lot.",
                        "Ekuiseo : un reversement vous attend, ajoutez un compte mobile money verifie dans l'application.");
            case IDENTITY_APPROVED:
                return finish("Identite verifiee",
                        "Votre piece d'identite a ete verifiee. Le badge e Identite verifiee u apparait desormais sur votre profil "
                                + "et rassure les passagers qui choisissent un conducteur.",
                        "Ekuiseo : votre identite est verifiee, le badge apparait sur votre profil.");
            case IDENTITY_REJECTED:
                return finish("Verification d'identite refusee",
                        "Votre demande de verification d'identite n'a pas ete acceptee." + reasonLine(p)
                                + "\n\nVous pouvez soumettre une nouvelle demande depuis Mon compte > Identite.",
                        "Ekuiseo : votre verification d'identite a ete refusee, consultez l'application.");
            case IDENTITY_REVOKED:
                return finish("Badge d'identite retire",
                        "Le badge e Identite verifiee u a ete retire de votre profil par notre equipe." + reasonLine(p)
                                + "\n\nVous pouvez soumettre une nouvelle demande de verification depuis Mon compte > Identite.",
                        "Ekuiseo : votre badge d'identite verifiee a ete retire, consultez l'application.");
            case ACCOUNT_SUSPENDED:
                return finish("Votre compte a ete suspendu",
                        "Votre compte Ekuiseo a ete suspendu par la moderation." + reasonLine(p)
                                + "\n\nVos trajets a venir et vos reservations en cours ont ete annules ; les acomptes concernes "
                                + "sont rembourses. Pour contester cette decision, repondez a ce message.",
                        "Ekuiseo : votre compte a ete suspendu" + (str(p, "reason").isEmpty() ? "." : " (" + str(p, "reason") + ")."));
            case REPORT_RESOLVED: {
                String status = str(p, "status");
                String outcome = "DISMISSED".equals(status)
                        ? "La moderation a examine votre signalement et n'a pas retenu de manquement."
                        : "La moderation a examine votre signalement et l'a traite.";
                String note = str(p, "resolutionNote");
                return finish("Votre signalement a ete traite",
                        outcome + (note.isEmpty() ? "" : "\nNote de la moderation : " + note)
                                + "\n\nMerci de contribuer a la securite de la communaute.",
                        "Ekuiseo : votre signalement a ete traite, consultez l'application.");
            }
            default:
                return finish("Notification Ekuiseo", "Vous avez une nouvelle notification dans l'application Ekuiseo.",
                        "Ekuiseo : vous avez une nouvelle notification.");
        }
    }

    /**
     * Construit un payload a partir de couples cle/valeur en ignorant les valeurs nulles
     * (contrairement a Map.of) : un prenom absent ne doit pas faire echouer une confirmation.
     */
    public static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    private static Rendered finish(String subject, String body, String sms) {
        return new Rendered(subject, "Bonjour,\n\n" + body + SIGNATURE, sms);
    }

    private static String tripLine(String route, String when) {
        if (route.isEmpty() && when.isEmpty()) return "";
        if (when.isEmpty()) return route;
        if (route.isEmpty()) return "du " + when;
        return route + " du " + when;
    }

    private static String refundLine(Map<String, Object> p) {
        if (!has(p, "refundAmountFcfa")) return "";
        long refund = number(p, "refundAmountFcfa");
        if (refund <= 0) {
            return has(p, "retainedAmountFcfa") && number(p, "retainedAmountFcfa") > 0
                    ? "\nL'acompte de " + money(p, "retainedAmountFcfa") + " est retenu (annulation tardive)." : "";
        }
        String line = "\nVotre acompte de " + money(p, "refundAmountFcfa") + " vous sera rembourse";
        if (has(p, "retainedAmountFcfa") && number(p, "retainedAmountFcfa") > 0) {
            line += " ; " + money(p, "retainedAmountFcfa") + " sont retenus (annulation tardive)";
        }
        return line + ".";
    }

    private static String reasonLine(Map<String, Object> p) {
        String reason = str(p, "reason");
        return reason.isEmpty() ? "" : "\nMotif : " + reason;
    }

    private static boolean has(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v != null && !(v instanceof String && ((String) v).isBlank());
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static long number(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** {@code 12500} devient {@code 12 500 FCFA}. */
    static String money(Map<String, Object> p, String key) {
        return formatFcfa(number(p, key));
    }

    static String formatFcfa(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sb.append(digits.charAt(i));
            if (++count % 3 == 0 && i > 0) sb.append(' ');
        }
        return (amount < 0 ? "-" : "") + sb.reverse() + " FCFA";
    }

    /** Instant ISO du payload formate a l heure du Benin ; la valeur brute si elle n est pas un instant. */
    private static String instant(Map<String, Object> p, String key) {
        String raw = str(p, key);
        if (raw.isEmpty()) return "";
        try {
            return DATE_TIME.format(Instant.parse(raw));
        } catch (DateTimeException | IllegalArgumentException e) {
            return raw;
        }
    }
}
