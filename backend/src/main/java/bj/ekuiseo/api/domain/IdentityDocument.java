package bj.ekuiseo.api.domain;

import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadonnees d une piece d identite televersee (V20). Le contenu n est pas en base : il est
 * chiffre sur le disque du serveur sous {@code storageKey} (voir {@code IdentityDocumentStorage}).
 * Une face par dossier ; le remplacement supprime l ancien fichier. Le nom d origine du fichier
 * n est jamais conserve.
 */
@Entity
@Table(name = "identity_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentityDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verification_id", nullable = false)
    private IdentityVerification verification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IdentityDocumentSide side;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    /** Nom du fichier chiffre dans le repertoire de stockage (UUID + extension technique). */
    @Column(name = "storage_key", nullable = false, length = 100)
    private String storageKey;

    /** Empreinte SHA-256 (hexadecimal) du contenu en clair : controle d integrite a la relecture. */
    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
