package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.common.exception.ServiceUnavailableException;
import bj.ekuiseo.api.domain.IdentityDocument;
import bj.ekuiseo.api.domain.IdentityVerification;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.IdentityDocumentSide;
import bj.ekuiseo.api.domain.enums.IdentityVerificationStatus;
import bj.ekuiseo.api.dto.user.IdentityDocumentSummary;
import bj.ekuiseo.api.repository.IdentityDocumentRepository;
import bj.ekuiseo.api.repository.IdentityVerificationRepository;
import bj.ekuiseo.api.service.storage.IdentityDocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V20 : depot borne au dossier PENDING, type etabli par les octets, remplacement d une face,
 * retrait, lecture back-office journalisee, purge des dossiers decides et anonymisation.
 */
class IdentityDocumentServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private final IdentityDocumentRepository documents = mock(IdentityDocumentRepository.class);
    private final IdentityVerificationRepository verifications = mock(IdentityVerificationRepository.class);
    private final IdentityDocumentStorage storage = mock(IdentityDocumentStorage.class);
    private final AuditService auditService = mock(AuditService.class);
    private final IdentityDocumentService service = new IdentityDocumentService(documents, verifications, storage, auditService);

    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").build();
    private final IdentityVerification pending = IdentityVerification.builder().id(UUID.randomUUID()).user(user)
            .status(IdentityVerificationStatus.PENDING).submittedAt(Instant.now()).build();

    @BeforeEach
    void setUp() throws IOException {
        when(storage.isEnabled()).thenReturn(true);
        when(storage.store(any())).thenReturn("11111111-2222-3333-4444-555555555555.bin");
        when(verifications.findByUserId(user.getId())).thenReturn(Optional.of(pending));
        when(verifications.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(documents.save(any(IdentityDocument.class))).thenAnswer(inv -> {
            IdentityDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    @Test
    void upload_storesEncrypted_recordsMetadataFromBytes_andAudits() throws IOException {
        when(documents.findByVerificationIdAndSide(pending.getId(), IdentityDocumentSide.FRONT)).thenReturn(Optional.empty());

        IdentityDocumentSummary summary = service.upload(user.getId(), IdentityDocumentSide.FRONT, JPEG);

        verify(storage).store(JPEG);
        ArgumentCaptor<IdentityDocument> saved = ArgumentCaptor.forClass(IdentityDocument.class);
        verify(documents).save(saved.capture());
        assertThat(saved.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(JPEG.length);
        assertThat(saved.getValue().getStorageKey()).endsWith(".bin");
        assertThat(saved.getValue().getSha256()).hasSize(64);
        assertThat(summary.side()).isEqualTo(IdentityDocumentSide.FRONT);
        assertThat(summary.contentType()).isEqualTo("image/jpeg");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(user.getId()), eq("IDENTITY_DOCUMENT_UPLOADED"), eq("identity_verification"),
                eq(pending.getId()), audit.capture());
        assertThat(audit.getValue()).containsEntry("side", "FRONT").containsEntry("replaced", false);
        verify(storage, never()).delete(any());
    }

    @Test
    void upload_replacesTheSameSide_andDeletesThePreviousFile() {
        IdentityDocument previous = IdentityDocument.builder().id(UUID.randomUUID()).verification(pending)
                .side(IdentityDocumentSide.FRONT).contentType("image/png").storageKey("00000000-0000-0000-0000-000000000000.bin").build();
        when(documents.findByVerificationIdAndSide(pending.getId(), IdentityDocumentSide.FRONT)).thenReturn(Optional.of(previous));

        service.upload(user.getId(), IdentityDocumentSide.FRONT, JPEG);

        verify(documents).save(previous);
        assertThat(previous.getContentType()).isEqualTo("image/jpeg");
        assertThat(previous.getStorageKey()).isEqualTo("11111111-2222-3333-4444-555555555555.bin");
        verify(storage).delete("00000000-0000-0000-0000-000000000000.bin");
    }

    @Test
    void upload_refusesAForgedType_emptyOrTooLargeContent_withoutTouchingTheDisk() throws IOException {
        byte[] script = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.upload(user.getId(), IdentityDocumentSide.BACK, script))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Format non reconnu");
        assertThatThrownBy(() -> service.upload(user.getId(), IdentityDocumentSide.BACK, new byte[0]))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload(user.getId(), IdentityDocumentSide.BACK, new byte[(int) IdentityDocumentService.MAX_SIZE_BYTES + 1]))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5 Mo");
        verify(storage, never()).store(any());
        verify(documents, never()).save(any());
    }

    @Test
    void upload_requiresAPendingDossier_andAnEnabledStorage() throws IOException {
        pending.setStatus(IdentityVerificationStatus.APPROVED);
        assertThatThrownBy(() -> service.upload(user.getId(), IdentityDocumentSide.FRONT, JPEG))
                .isInstanceOf(ConflictException.class);

        when(verifications.findByUserId(user.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(user.getId(), IdentityDocumentSide.FRONT, JPEG))
                .isInstanceOf(NotFoundException.class);

        when(storage.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.upload(user.getId(), IdentityDocumentSide.FRONT, JPEG))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(storage, never()).store(any());
    }

    @Test
    void delete_removesRowAndFile_whilePending_only() {
        IdentityDocument doc = IdentityDocument.builder().id(UUID.randomUUID()).verification(pending)
                .side(IdentityDocumentSide.SELFIE).storageKey("k.bin").build();
        when(documents.findByVerificationIdAndSide(pending.getId(), IdentityDocumentSide.SELFIE)).thenReturn(Optional.of(doc));

        service.delete(user.getId(), IdentityDocumentSide.SELFIE);
        verify(documents).delete(doc);
        verify(storage).delete("k.bin");
        verify(auditService).log(eq(user.getId()), eq("IDENTITY_DOCUMENT_DELETED"), any(), eq(pending.getId()), any());

        pending.setStatus(IdentityVerificationStatus.REJECTED);
        assertThatThrownBy(() -> service.delete(user.getId(), IdentityDocumentSide.SELFIE)).isInstanceOf(ConflictException.class);
    }

    @Test
    void adminRead_returnsDecryptedContent_andIsAudited() throws IOException {
        UUID adminId = UUID.randomUUID();
        IdentityDocument doc = IdentityDocument.builder().id(UUID.randomUUID()).verification(pending)
                .side(IdentityDocumentSide.FRONT).contentType("image/jpeg").storageKey("k.bin").sizeBytes(JPEG.length).build();
        when(documents.findByVerificationIdAndSide(pending.getId(), IdentityDocumentSide.FRONT)).thenReturn(Optional.of(doc));
        when(storage.read("k.bin")).thenReturn(JPEG);

        IdentityDocumentService.StoredDocument stored = service.openForAdmin(adminId, pending.getId(), IdentityDocumentSide.FRONT);

        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.content()).isEqualTo(JPEG);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq(adminId), eq(IdentityDocumentService.AUDIT_VIEWED), eq("identity_verification"),
                eq(pending.getId()), audit.capture());
        assertThat(audit.getValue()).containsEntry("userId", user.getId().toString()).containsEntry("side", "FRONT");

        // Face absente : 404, rien de journalise de plus.
        when(documents.findByVerificationIdAndSide(pending.getId(), IdentityDocumentSide.BACK)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.openForAdmin(adminId, pending.getId(), IdentityDocumentSide.BACK))
                .isInstanceOf(NotFoundException.class);
        verify(auditService, org.mockito.Mockito.times(1)).log(any(), any(), any(), any(), any());
    }

    @Test
    void purgeAndAnonymization_deleteRowsAndFiles() {
        IdentityDocument a = IdentityDocument.builder().id(UUID.randomUUID()).verification(pending).side(IdentityDocumentSide.FRONT).storageKey("a.bin").build();
        IdentityDocument b = IdentityDocument.builder().id(UUID.randomUUID()).verification(pending).side(IdentityDocumentSide.BACK).storageKey("b.bin").build();
        Instant cutoff = Instant.now().minusSeconds(30L * 24 * 3600);
        when(documents.findDecidedBefore(cutoff)).thenReturn(List.of(a, b));
        when(documents.findByUserId(user.getId())).thenReturn(List.of(a));

        assertThat(service.purgeDecidedBefore(cutoff)).isEqualTo(2);
        verify(documents).delete(a);
        verify(documents).delete(b);
        verify(storage).delete("a.bin");
        verify(storage).delete("b.bin");

        assertThat(service.deleteAllForUser(user.getId())).isEqualTo(1);
    }
}
