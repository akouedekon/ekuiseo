package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.BadRequestException;
import bj.ekuiseo.api.domain.PushSubscription;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.push.PushSubscriptionRequest;
import bj.ekuiseo.api.repository.PushSubscriptionRepository;
import bj.ekuiseo.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V20 : upsert par endpoint, plafond de trois appareils (le plus ancien evince), endpoint HTTPS obligatoire. */
class PushSubscriptionServiceTest {

    private final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PushSubscriptionService service = new PushSubscriptionService(repository, userRepository);

    private final User user = User.builder().id(UUID.randomUUID()).firstName("Awa").build();
    private final User other = User.builder().id(UUID.randomUUID()).firstName("Koffi").build();

    @BeforeEach
    void setUp() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(repository.save(any(PushSubscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PushSubscriptionRequest request(String endpoint) {
        return new PushSubscriptionRequest(endpoint, new PushSubscriptionRequest.Keys("BNcR...", "tBHI..."));
    }

    private PushSubscription existing(User owner, String endpoint, Instant createdAt) {
        return PushSubscription.builder().id(UUID.randomUUID()).user(owner).endpoint(endpoint)
                .p256dh("old").auth("old").createdAt(createdAt).failures(3).build();
    }

    @Test
    void firstSubscription_isStoredWithKeysAndUserAgent() {
        when(repository.findByEndpoint("https://fcm.googleapis.com/fcm/send/abc")).thenReturn(Optional.empty());
        when(repository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of());

        service.subscribe(user.getId(), request("https://fcm.googleapis.com/fcm/send/abc"), "Mozilla/5.0 (Android)");

        ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getEndpoint()).isEqualTo("https://fcm.googleapis.com/fcm/send/abc");
        assertThat(saved.getValue().getP256dh()).isEqualTo("BNcR...");
        assertThat(saved.getValue().getAuth()).isEqualTo("tBHI...");
        assertThat(saved.getValue().getUserAgent()).isEqualTo("Mozilla/5.0 (Android)");
        assertThat(saved.getValue().getLastUsedAt()).isNotNull();
        verify(repository, never()).delete(any());
    }

    @Test
    void sameEndpoint_isUpdatedInPlace_andReassignedToTheConnectedAccount() {
        PushSubscription row = existing(other, "https://push.example/x", Instant.now());
        when(repository.findByEndpoint("https://push.example/x")).thenReturn(Optional.of(row));

        service.subscribe(user.getId(), request("https://push.example/x"), null);

        verify(repository).save(row);
        assertThat(row.getUser()).isSameAs(user);
        assertThat(row.getP256dh()).isEqualTo("BNcR...");
        assertThat(row.getFailures()).isZero();
        verify(repository, never()).findByUserIdOrderByCreatedAtAsc(any());
    }

    @Test
    void fourthDevice_evictsTheOldest() {
        Instant now = Instant.now();
        PushSubscription oldest = existing(user, "https://push.example/1", now.minusSeconds(3000));
        PushSubscription middle = existing(user, "https://push.example/2", now.minusSeconds(2000));
        PushSubscription newest = existing(user, "https://push.example/3", now.minusSeconds(1000));
        when(repository.findByEndpoint("https://push.example/4")).thenReturn(Optional.empty());
        when(repository.findByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(oldest, middle, newest));

        service.subscribe(user.getId(), request("https://push.example/4"), "ua");

        verify(repository).delete(oldest);
        verify(repository, never()).delete(middle);
        verify(repository, never()).delete(newest);
        verify(repository).save(any(PushSubscription.class));
    }

    @Test
    void endpoint_mustBeHttps() {
        assertThatThrownBy(() -> service.subscribe(user.getId(), request("http://push.example/x"), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.unsubscribe(user.getId(), "javascript:alert(1)"))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void unsubscribe_onlyRemovesTheCallersEndpoint() {
        service.unsubscribe(user.getId(), " https://push.example/x ");
        verify(repository).deleteByUserIdAndEndpoint(user.getId(), "https://push.example/x");
    }
}
