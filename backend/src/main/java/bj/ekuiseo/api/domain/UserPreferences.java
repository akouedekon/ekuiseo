package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.ChattyLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Preferences d'un utilisateur : canaux de notification et preferences a bord
 * (fumeur, musique, animaux, bavardage). Une ligne par utilisateur, creee a la
 * premiere consultation/modification (voir UserPreferencesService) plutot qu'a
 * l'inscription, pour ne pas alourdir AuthService.
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreferences {

    @Id
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "notify_by_push", nullable = false)
    @Builder.Default
    private boolean notifyByPush = true;

    @Column(name = "notify_by_sms", nullable = false)
    @Builder.Default
    private boolean notifyBySms = true;

    @Column(name = "notify_by_email", nullable = false)
    @Builder.Default
    private boolean notifyByEmail = false;

    @Column(nullable = false, length = 5)
    @Builder.Default
    private String language = "fr";

    @Column(nullable = false)
    @Builder.Default
    private boolean smoking = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean music = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean pets = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ChattyLevel chatty = ChattyLevel.DEPENDS;
}
