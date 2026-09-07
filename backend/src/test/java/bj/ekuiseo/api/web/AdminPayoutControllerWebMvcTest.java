package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.common.exception.NotFoundException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.MobileMoneyOperator;
import bj.ekuiseo.api.domain.enums.PayoutStatus;
import bj.ekuiseo.api.dto.payout.AdminPayoutResponse;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import bj.ekuiseo.api.dto.payout.PayoutResponse;
import bj.ekuiseo.api.dto.payout.SettlePayoutRequest;
import bj.ekuiseo.api.service.PayoutService;
import bj.ekuiseo.api.web.controller.admin.AdminPayoutController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/admin/payouts/**} (regle metier n.4, constat F434) : reserve a ROLE_ADMIN,
 * l'administrateur qui declenche ou regle un lot est le sujet du jeton (journal d'audit).
 * L alias {@code /pay} a ete retire (constat F015) : le reglement passe par {@code /settle},
 * avec ou sans corps, et le statut renvoye est SETTLED partout (constat F455).
 */
@WebMvcTest(controllers = AdminPayoutController.class)
class AdminPayoutControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String PATH = "/api/v1/admin/payouts";

    @MockitoBean
    private PayoutService payoutService;

    @Test
    void anonymous_is401_andRegularUserIs403_onEveryRoute() throws Exception {
        String userBearer = bearerFor(activeUser());
        UUID payoutId = UUID.randomUUID();

        mockMvc.perform(fromNewIp(get(PATH))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(post(PATH + "/run"))).andExpect(status().isUnauthorized());
        mockMvc.perform(authed(get(PATH), userBearer)).andExpect(status().isForbidden());
        mockMvc.perform(authed(post(PATH + "/run"), userBearer))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));
        mockMvc.perform(authed(post(PATH + "/" + payoutId + "/settle"), userBearer)).andExpect(status().isForbidden());

        verify(payoutService, never()).listAllForAdmin(any(), anyInt(), anyInt());
        verify(payoutService, never()).runWeeklyBatch(any());
        verify(payoutService, never()).settle(any(), any(), any(), any());
    }

    @Test
    void legacyPayAlias_isGone() throws Exception {
        mockMvc.perform(authed(post(PATH + "/" + UUID.randomUUID() + "/pay"), bearerFor(activeAdmin())))
                .andExpect(status().isNotFound());
        verify(payoutService, never()).settle(any(), any(), any(), any());
    }

    @Test
    void list_asAdmin_returnsBackOfficePage_withFilterAndPaging() throws Exception {
        User admin = activeAdmin();
        UUID payoutId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AdminPayoutResponse row = new AdminPayoutResponse(
                payoutId, driverId, "Awa Test", MobileMoneyOperator.MTN_MOMO, "+2290197000322",
                4600, 2, now.minus(7, ChronoUnit.DAYS), now, PayoutStatus.PENDING, null, 0, 0, null, null, null, null);
        when(payoutService.listAllForAdmin(PayoutStatus.PENDING, 1, 10))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(1, 10), 11));

        mockMvc.perform(authed(get(PATH).param("status", "PENDING").param("page", "1").param("size", "10"), bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content[0].id").value(payoutId.toString()))
                .andExpect(jsonPath("$.content[0].driverName").value("Awa Test"))
                .andExpect(jsonPath("$.content[0].provider").value("MTN_MOMO"))
                .andExpect(jsonPath("$.content[0].amount").value(4600))
                .andExpect(jsonPath("$.content[0].tripCount").value(2))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].paidAt").doesNotExist());
        verify(payoutService).listAllForAdmin(PayoutStatus.PENDING, 1, 10);
    }

    @Test
    void list_withoutParameters_usesFirstPageOfTwenty_andNoFilter() throws Exception {
        when(payoutService.listAllForAdmin(isNull(), eq(0), eq(20))).thenReturn(Page.empty());

        mockMvc.perform(authed(get(PATH), bearerFor(activeAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(payoutService).listAllForAdmin(null, 0, 20);
    }

    @Test
    void run_asAdmin_passesAdminIdFromToken_andReturnsBatchResult() throws Exception {
        User admin = activeAdmin();
        UUID driverId = UUID.randomUUID();
        PayoutResponse created = payout(UUID.randomUUID(), driverId, 2600, PayoutStatus.PENDING);
        when(payoutService.runWeeklyBatch(admin.getId())).thenReturn(new PayoutBatchResultResponse(
                1, 2600, List.of(created),
                List.of(new PayoutBatchResultResponse.SkippedDriver(UUID.randomUUID(), "Sans Compte", 3000,
                        "Aucun compte mobile money verifie"))));

        mockMvc.perform(authed(post(PATH + "/run"), bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutsCreated").value(1))
                .andExpect(jsonPath("$.totalAmountFcfa").value(2600))
                .andExpect(jsonPath("$.payouts[0].driverId").value(driverId.toString()))
                .andExpect(jsonPath("$.payouts[0].status").value("PENDING"))
                .andExpect(jsonPath("$.skipped[0].driverName").value("Sans Compte"))
                .andExpect(jsonPath("$.skipped[0].amountFcfa").value(3000));
        verify(payoutService).runWeeklyBatch(admin.getId());
    }

    @Test
    void settle_withoutBody_settlesWithAdminAndPayoutIds() throws Exception {
        User admin = activeAdmin();
        UUID payoutId = UUID.randomUUID();
        when(payoutService.settle(admin.getId(), payoutId, null, null))
                .thenReturn(payout(payoutId, UUID.randomUUID(), 2600, PayoutStatus.SETTLED));

        mockMvc.perform(authed(post(PATH + "/" + payoutId + "/settle"), bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payoutId.toString()))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settledAt").exists());
        verify(payoutService).settle(admin.getId(), payoutId, null, null);
    }

    @Test
    void settle_withBody_passesReferenceAndAmount() throws Exception {
        User admin = activeAdmin();
        UUID payoutId = UUID.randomUUID();
        when(payoutService.settle(eq(admin.getId()), eq(payoutId), eq("MP240907"), eq(2600L)))
                .thenReturn(payout(payoutId, UUID.randomUUID(), 2600, PayoutStatus.SETTLED));

        mockMvc.perform(authed(json(post(PATH + "/" + payoutId + "/settle"), new SettlePayoutRequest("MP240907", 2600L)), bearerFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
        verify(payoutService).settle(admin.getId(), payoutId, "MP240907", 2600L);
    }

    @Test
    void settle_unknownPayout_is404() throws Exception {
        when(payoutService.settle(any(), any(), isNull(), isNull())).thenThrow(new NotFoundException("Reversement introuvable"));

        mockMvc.perform(authed(post(PATH + "/" + UUID.randomUUID() + "/settle"), bearerFor(activeAdmin())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/not-found"))
                .andExpect(jsonPath("$.detail").value("Reversement introuvable"));
    }

    @Test
    void settle_alreadySettled_is409() throws Exception {
        when(payoutService.settle(any(), any(), isNull(), isNull())).thenThrow(new ConflictException("Reversement deja regle"));

        mockMvc.perform(authed(post(PATH + "/" + UUID.randomUUID() + "/settle"), bearerFor(activeAdmin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    private static PayoutResponse payout(UUID id, UUID driverId, long amount, PayoutStatus status) {
        Instant now = Instant.now();
        return new PayoutResponse(id, driverId, amount, status, "+2290197000322",
                now.minus(7, ChronoUnit.DAYS), now, now, status == PayoutStatus.SETTLED ? now : null);
    }
}
