package bj.ekuiseo.api.service.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V20 : chiffrement AES-256-GCM aller-retour, fichier illisible en clair, cle invalide refusee, type par octets magiques. */
class IdentityDocumentStorageTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 2, 3};

    @TempDir
    Path dir;

    @Test
    void store_thenRead_roundTrips_andTheFileOnDiskIsNotThePlainContent() throws IOException {
        IdentityDocumentStorage storage = new IdentityDocumentStorage(dir.toString(), KEY);
        assertThat(storage.isEnabled()).isTrue();

        String key = storage.store(JPEG);

        assertThat(key).matches("[0-9a-f-]{36}\\.bin");
        Path file = dir.resolve(key);
        assertThat(file).exists();
        byte[] onDisk = Files.readAllBytes(file);
        assertThat(onDisk.length).isGreaterThan(JPEG.length);
        assertThat(IdentityDocumentStorage.sniffContentType(onDisk)).isNull();
        assertThat(storage.read(key)).isEqualTo(JPEG);

        assertThat(storage.delete(key)).isTrue();
        assertThat(file).doesNotExist();
        assertThat(storage.delete(key)).isFalse();
    }

    @Test
    void read_refusesATamperedFile_andAnotherKey() throws IOException {
        IdentityDocumentStorage storage = new IdentityDocumentStorage(dir.toString(), KEY);
        String key = storage.store("contenu".getBytes(StandardCharsets.UTF_8));

        Path file = dir.resolve(key);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(IOException.class);

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        IdentityDocumentStorage other = new IdentityDocumentStorage(dir.toString(), Base64.getEncoder().encodeToString(otherKey));
        String key2 = storage.store("contenu".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> other.read(key2)).isInstanceOf(IOException.class);
    }

    @Test
    void storageKey_isValidated_noPathTraversal() {
        IdentityDocumentStorage storage = new IdentityDocumentStorage(dir.toString(), KEY);
        assertThatThrownBy(() -> storage.read("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.delete("../x.bin")).isFalse();
    }

    @Test
    void disabledWithoutKey_andInvalidKeyRefusesToStart() {
        IdentityDocumentStorage disabled = new IdentityDocumentStorage(dir.toString(), "");
        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.store(JPEG)).isInstanceOf(IOException.class);

        assertThatThrownBy(() -> new IdentityDocumentStorage(dir.toString(), "pas-du-base64!"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdentityDocumentStorage(dir.toString(), Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 octets");
    }

    @Test
    void contentType_comesFromMagicBytes_neverFromTheClaim() {
        assertThat(IdentityDocumentStorage.sniffContentType(JPEG)).isEqualTo("image/jpeg");
        assertThat(IdentityDocumentStorage.sniffContentType(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}))
                .isEqualTo("image/png");
        assertThat(IdentityDocumentStorage.sniffContentType("RIFF....WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("image/webp");
        assertThat(IdentityDocumentStorage.sniffContentType("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("application/pdf");
        // Un executable ou un script renomme en .jpg n est pas une image.
        assertThat(IdentityDocumentStorage.sniffContentType("MZ......".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(IdentityDocumentStorage.sniffContentType("<script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(IdentityDocumentStorage.sniffContentType(new byte[0])).isNull();
        assertThat(IdentityDocumentStorage.sniffContentType(null)).isNull();
    }

    @Test
    void sha256_isHexOf64Characters() {
        assertThat(IdentityDocumentStorage.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
