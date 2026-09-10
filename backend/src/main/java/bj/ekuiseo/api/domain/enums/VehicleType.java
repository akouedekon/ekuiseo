package bj.ekuiseo.api.domain.enums;

/**
 * Type de vehicule (V22). Au Benin, moto (zemidjan) et tricycle sont des moyens de
 * transport partage a part entiere : ils conditionnent le nombre de places offertes.
 */
public enum VehicleType {
    /** Voiture, minibus : jusqu a 8 passagers. */
    CAR(8),
    /** Moto : un seul passager, casque obligatoire. */
    MOTO(1),
    /** Tricycle motorise : jusqu a 6 passagers. */
    TRICYCLE(6);

    private final int maxSeats;

    VehicleType(int maxSeats) {
        this.maxSeats = maxSeats;
    }

    /** Nombre maximal de places (hors conducteur) qu un vehicule de ce type peut declarer. */
    public int maxSeats() {
        return maxSeats;
    }
}
