package bj.ekuiseo.api.web;

import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.dto.finance.FinanceSummaryResponse;
import bj.ekuiseo.api.dto.finance.ReconciliationRunResponse;
import bj.ekuiseo.api.dto.payout.DriverEarningsResponse;
import bj.ekuiseo.api.service.DriverEarningsService;
import bj.ekuiseo.api.service.LedgerService;
import bj.ekuiseo.api.service.PaymentEventService;
import bj.ekuiseo.api.service.PaymentWebhookService;
import bj.ekuiseo.api.service.ReconciliationService;
import bj.ekuiseo.api.service.RefundService;
import bj.ekuiseo.api.service.admin.AdminBookingService;
import bj.ekuiseo.api.web.controller.MeEarningsController;
import bj.ekuiseo.api.web.controller.admin.AdminBookingController;
import bj.ekuiseo.api.web.controller.admin.AdminFinanceController;
import bj.ekuiseo.api.web.controller.admin.AdminPaymentController;
import bj.ekuiseo.api.web.controller.admin.AdminRefundController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regles 401 / 403 sur les nouvelles routes du lot A (finance, remboursements, reservations admin,
 * revenus conducteur), quota du rapprochement (5 / min), et validation : les corps ne portent que
 * des identifiants, jamais un montant venant du client (sauf la correction admin, journalisee).
 */
@WebMvcTest(controllers = {AdminFinanceController.class, AdminRefundController.class, AdminBookingController.class,
        AdminPaymentController.class, MeEarningsController.class})
class AdminFinanceSecurityWebMvcTest extends AbstractWebMvcTest {

    @MockitoBean
    private LedgerService ledgerService;
    @MockitoBean
    private ReconciliationService reconciliationService;
    @MockitoBean
    private RefundService refundService;
    @MockitoBean
    private AdminBookingService adminBookingService;
    @MockitoBean
    private PaymentEventService paymentEventService;
    @MockitoBean
    private PaymentWebhookService paymentWebhookService;
    @MockitoBean
    private DriverEarningsService driverEarningsService;

    private static final List<String> ADMIN_GETS = List.of(
            "/api/v1/admin/finance/summary", "/api/v1/admin/finance/ledger", "/api/v1/admin/finance/ledger/export",
            "/api/v1/admin/finance/reconciliation/runs", "/api/v1/admin/finance/reconciliation/anomalies",
            "/api/v1/admin/refunds", "/api/v1/admin/bookings", "/api/v1/admin/bookings/" + UUID.randomUUID(),
            "/api/v1/admin/payments/webhooks", "/api/v1/admin/payments/" + UUID.randomUUID() + "/events");

    @Test
    void anonymous_is401_onEveryNewRoute() throws Exception {
        for (String path : ADMIN_GETS) {
            mockMvc.perform(fromNewIp(get(path))).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        mockMvc.perform(fromNewIp(post("/api/v1/admin/finance/reconciliation/run"))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(post("/api/v1/admin/refunds/" + UUID.randomUUID() + "/retry"))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(get("/api/v1/me/earnings"))).andExpect(status().isUnauthorized());
        verify(ledgerService, never()).summary(anyInt());
        verify(driverEarningsService, never()).compute(any());
    }

    @Test
    void regularUser_is403_onAdminRoutes_but200OnEarnings() throws Exception {
        User user = activeUser();
        String bearer = bearerFor(user);
        for (String path : ADMIN_GETS) {
            mockMvc.perform(authed(get(path), bearer)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/forbidden"));
        }
        mockMvc.perform(authed(post("/api/v1/admin/finance/reconciliation/run"), bearer)).andExpect(status().isForbidden());
        mockMvc.perform(authed(json(post("/api/v1/admin/finance/ledger/adjustments"),
                java.util.Map.of("account", "PLATFORM", "direction", "DEBIT", "amountFcfa", 500, "description", "x")), bearer))
                .andExpect(status().isForbidden());
        verify(ledgerService, never()).adjust(any(), any());

        when(driverEarningsService.compute(user.getId())).thenReturn(new DriverEarningsResponse(2600, 0, 0, 0, 7500, 600, 0,
                1, 0, 3, null, 0, 2000, Instant.now(), false, List.of()));
        mockMvc.perform(authed(get("/api/v1/me/earnings"), bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceFcfa").value(2600))
                .andExpect(jsonPath("$.minimumPayoutFcfa").value(2000));
        verify(driverEarningsService).compute(user.getId());
    }

    @Test
    void admin_is200_andExportIsCsvWithBom() throws Exception {
        String bearer = bearerFor(activeAdmin());
        when(ledgerService.summary(30)).thenReturn(new FinanceSummaryResponse(30, 1000, 19, 600, 400, 0, 0, 0, 0, 0, 0, List.of()));
        when(ledgerService.search(any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(ledgerService.exportCsv(any(), any())).thenReturn("﻿id;date\n");
        when(refundService.listRefundsForAdmin(any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(adminBookingService.search(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(authed(get("/api/v1/admin/finance/summary"), bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platformCommissionFcfa").value(600));
        mockMvc.perform(authed(get("/api/v1/admin/finance/ledger").param("entryType", "REFUND"), bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(authed(get("/api/v1/admin/finance/ledger/export").param("from", "2026-09-01").param("to", "2026-09-10"), bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("registre-financier.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
        verify(ledgerService).exportCsv(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z"));
        mockMvc.perform(authed(get("/api/v1/admin/refunds").param("status", "MANUAL_REVIEW"), bearer)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/admin/bookings").param("q", "0197"), bearer)).andExpect(status().isOk());
        verify(adminBookingService).search(eq("0197"), any(), any(), any(), eq(0), eq(20));
    }

    @Test
    void adjustment_requiresDescription_andPositiveAmount() throws Exception {
        String bearer = bearerFor(activeAdmin());
        mockMvc.perform(authed(json(post("/api/v1/admin/finance/ledger/adjustments"),
                java.util.Map.of("account", "PLATFORM", "direction", "DEBIT", "amountFcfa", 500, "description", "  ")), bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/validation-error"));
        mockMvc.perform(authed(json(post("/api/v1/admin/finance/ledger/adjustments"),
                java.util.Map.of("account", "PLATFORM", "direction", "DEBIT", "amountFcfa", 0, "description", "ok")), bearer))
                .andExpect(status().isBadRequest());
        verify(ledgerService, never()).adjust(any(), any());
    }

    /** Rapprochement : 5 lancements par minute et par administrateur, puis 429 avec Retry-After. */
    @Test
    void reconciliationRun_isRateLimited() throws Exception {
        User admin = activeAdmin();
        String bearer = bearerFor(admin);
        when(reconciliationService.run(eq(admin.getId()), anyInt())).thenReturn(new ReconciliationRunResponse(
                UUID.randomUUID(), "MANUAL", Instant.now(), Instant.now(), "DONE", 0, 0, null));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(authed(json(post("/api/v1/admin/finance/reconciliation/run"), java.util.Map.of("days", 7)), bearer))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(authed(post("/api/v1/admin/finance/reconciliation/run"), bearer))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        verify(reconciliationService, org.mockito.Mockito.times(5)).run(eq(admin.getId()), eq(7));
    }
}
