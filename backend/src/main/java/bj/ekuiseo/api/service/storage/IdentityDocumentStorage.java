package bj.ekuiseo.api.service.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stockage chiffre des pieces d identite (V20), sur le disque local du serveur, sans
 * prestataire : chaque fichier est chiffre en AES-256-GCM avec la cle
 * {@code ekuiseo.storage.identity-key} (IDENTITY_STORAGE_KEY, 32 octets en base64) sous un nom
 * technique aleatoire (UUID) dans {@code ekuiseo.storage.identity-dir}. Le nom d origine n est
 * jamais conserve ; le nom du fichier sert de donnee associee (AAD) au chiffrement, un
 * fichier renomme ne se dechiffre donc pas.
 *
 * <p>Cle absente : televersement desactive ({@link #isEnabled()} faux, 503 explicite cote API).
 * Cle presente mais invalide : refus de demarrer, comme pour la cle JWT, plutot qu un
 * chiffrement silencieusement affaibli.</p>
 *
 * <p>Format d un fichier : un octet de version (1), 12 octets de nonce, puis le chiffre GCM
 * (etiquette de 128 bits incluse).</p>
 */
@Component
public class IdentityDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(IdentityDocumentStorage.class);

    static final int KEY_BYTES = 32;
    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String EXTENSION = ".bin";
    /** Seuls des noms produits par {@link #store} sont acceptes en lecture/suppression : aucune traversee de repertoire possible. */
    private static final Pattern STORAGE_KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\" + EXTENSION);

    private final Path directory;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public IdentityDocumentStorage(@Value("${ekuiseo.storage.identity-dir:./data/identity}") String directory,
                                   @Value("${ekuiseo.storage.identity-key:}") String keyBase64) {
        this.directory = Paths.get(directory == null || directory.isBlank() ? "./data/identity" : directory.trim())
                .toAbsolutePath().normalize();
        this.key = parseKey(keyBase64);
        if (this.key == null) {
            log.info("Televersement des pieces d identite desactive : IDENTITY_STORAGE_KEY non renseignee");
        } else {
            try {
                Files.createDirectories(this.directory);
                log.info("Pieces d identite chiffrees dans {}", this.directory);
            } catch (IOException ex) {
                log.warn("Repertoire des pieces d identite {} non accessible : {}", this.directory, ex.getMessage());
            }
        }
    }

    static SecretKey parseKey(String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("IDENTITY_STORAGE_KEY doit etre en base64 (openssl rand -base64 32)");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("IDENTITY_STORAGE_KEY doit faire " + KEY_BYTES + " octets une fois decodee (openssl rand -base64 32)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    public boolean isEnabled() {
        return key != null;
    }

    public Path directory() {
        return directory;
    }

    /** Chiffre et ecrit le contenu ; renvoie la cle de stockage (nom de fichier) a conserver en base. */
    public String store(byte[] plain) throws IOException {
        requireEnabled();
        String storageKey = UUID.randomUUID() + EXTENSION;
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] encrypted;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(storageKey.getBytes(StandardCharsets.US_ASCII));
            encrypted = cipher.doFinal(plain);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Chiffrement impossible", ex);
        }
        byte[] out = new byte[1 + NONCE_BYTES + encrypted.length];
        out[0] = FORMAT_VERSION;
        System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
        System.arraycopy(encrypted, 0, out, 1 + NONCE_BYTES, encrypted.length);

        Files.createDirectories(directory);
        Path target = resolve(storageKey);
        Path temp = directory.resolve(storageKey + ".tmp");
        Files.write(temp, out);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return storageKey;
    }

    /** Relit et dechiffre un fichier ; IOException si absent, altere ou chiffre avec une autre cle. */
    public byte[] read(String storageKey) throws IOException {
        requireEnabled();
        byte[] data = Files.readAllBytes(resolve(storageKey));
        if (data.length < 1 + NONCE_BYTES + TAG_BITS / 8 || data[0] != FORMAT_VERSION) {
            throw new IOException("Fichier de piece d identite illisible");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, NONCE_BYTES));
            cipher.updateAAD(storageKey.getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(data, 1 + NONCE_BYTES, data.length - 1 - NONCE_BYTES);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Dechiffrement impossible (fichier altere ou cle differente)", ex);
        }
    }

    /** Supprime le fichier ; vrai s il existait. Ne leve pas si la cle est absente (purges). */
    public boolean delete(String storageKey) {
        try {
            return Files.deleteIfExists(resolve(storageKey));
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Suppression du fichier {} impossible : {}", storageKey, ex.getMessage());
            return false;
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Cle de stockage invalide");
        }
        return directory.resolve(storageKey);
    }

    private void requireEnabled() throws IOException {
        if (!isEnabled()) {
            throw new IOException("Stockage des pieces d identite desactive (IDENTITY_STORAGE_KEY absente)");
        }
    }

    /* ------------------------------------------------------------ Utilitaires de contenu */

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    /**
     * Type MIME reconnu par les octets magiques (jamais par l extension ni le Content-Type
     * declare, qu un client peut falsifier) : image/jpeg, image/png, image/webp,
     * application/pdf ; null sinon.
     */
    public static String sniffContentType(byte[] bytes) {
        if (bytes == null) return null;
        if (startsWith(bytes, 0, JPEG)) return "image/jpeg";
        if (startsWith(bytes, 0, PNG)) return "image/png";
        if (startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP)) return "image/webp";
        if (startsWith(bytes, 0, PDF)) return "application/pdf";
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) return false;
        return Arrays.equals(Arrays.copyOfRange(bytes, offset, offset + prefix.length), prefix);
    }

    /** Empreinte SHA-256 hexadecimale (64 caracteres) du contenu en clair. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 indisponible", ex);
        }
    }
}
