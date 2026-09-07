package bj.ekuiseo.api.service;

import bj.ekuiseo.api.domain.AuditLog;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.audit.AuditLogResponse;
import bj.ekuiseo.api.mapper.AuditLogMapper;
import bj.ekuiseo.api.repository.AuditLogRepository;
import bj.ekuiseo.api.repository.UserRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Constat F313 : journal filtrable (Specification) avec le nom des acteurs resolu en une requete. */
class AuditServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditService service = new AuditService(repository, userRepository, mapper);

    @Test
    @SuppressWarnings("unchecked")
    void search_resolvesActorNames_inOneQuery_andLeavesSystemEntriesAnonymous() {
        UUID adminId = UUID.randomUUID();
        AuditLog byAdmin = AuditLog.builder().id(UUID.randomUUID()).actorId(adminId).action("USER_SUSPENDED").build();
        AuditLog bySystem = AuditLog.builder().id(UUID.randomUUID()).actorId(null).action("BOOKING_EXPIRED").build();
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(byAdmin, bySystem)));
        when(userRepository.findAllById(anyIterable()))
                .thenReturn(List.of(User.builder().id(adminId).firstName("Lenaic").lastName("A").build()));
        when(mapper.toResponse(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog a = inv.getArgument(0);
            return new AuditLogResponse(a.getId(), a.getActorId(), null, a.getAction(), null, null, Map.of(), Instant.now());
        });

        Page<AuditLogResponse> page = service.search(new AuditService.Filter("USER_SUSPENDED", null, null, null, null, null),
                PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).actorName()).isEqualTo("Lenaic A");
        assertThat(page.getContent().get(1).actorName()).isNull();
        verify(userRepository, times(1)).findAllById(anyIterable());
    }

    @Test
    @SuppressWarnings("unchecked")
    void specification_addsOnePredicatePerActiveFilter_andOrdersByDateDesc() {
        Root<AuditLog> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> path = mock(Path.class);
        when(root.get(anyString())).thenReturn(path);
        when(cb.equal(any(), any(Object.class))).thenReturn(mock(Predicate.class));
        when(cb.greaterThanOrEqualTo(any(), any(Instant.class))).thenReturn(mock(Predicate.class));
        when(cb.lessThan(any(), any(Instant.class))).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(cb.desc(any())).thenReturn(mock(jakarta.persistence.criteria.Order.class));

        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-08T00:00:00Z");
        AuditService.Filter filter = new AuditService.Filter("user_suspended", UUID.randomUUID(), "user", UUID.randomUUID(), from, to);
        AuditService.toSpecification(filter).toPredicate(root, (CriteriaQuery<Object>) (CriteriaQuery<?>) query, cb);

        verify(cb).equal(path, "USER_SUSPENDED");
        verify(cb, times(4)).equal(any(), any(Object.class));
        verify(cb).greaterThanOrEqualTo(any(), eq(from));
        verify(cb).lessThan(any(), eq(to));
        verify(query).orderBy(any(jakarta.persistence.criteria.Order.class));

        // Aucun filtre : aucun predicat d egalite.
        CriteriaBuilder emptyCb = mock(CriteriaBuilder.class);
        when(emptyCb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        AuditService.toSpecification(AuditService.Filter.none()).toPredicate(root, (CriteriaQuery<Object>) (CriteriaQuery<?>) query, emptyCb);
        verify(emptyCb, times(0)).equal(any(), any(Object.class));
    }
}
