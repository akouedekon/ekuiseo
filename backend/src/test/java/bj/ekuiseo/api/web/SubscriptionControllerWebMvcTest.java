package bj.ekuiseo.api.web;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.domain.User;
import bj.ekuiseo.api.domain.enums.SubscriptionStatus;
import bj.ekuiseo.api.dto.payment.InitiatePaymentResponse;
import bj.ekuiseo.api.dto.subscription.SubscriptionResponse;
import bj.ekuiseo.api.service.SubscriptionService;
import bj.ekuiseo.api.service.kkiapay.KkiapayUnavailableException;
import bj.ekuiseo.api.web.controller.SubscriptionController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/v1/me/subscription} (regle metier n.10, constat F434) : le conducteur est toujours le sujet du jeton. */
@WebMvcTest(controllers = SubscriptionController.class)
class SubscriptionControllerWebMvcTest extends AbstractWebMvcTest {

    private static final String PATH = "/api/v1/me/subscription";

    @MockitoBean
    private SubscriptionService subscriptionService;

    @Test
    void anonymous_is401() throws Exception {
        mockMvc.perform(fromNewIp(get(PATH))).andExpect(status().isUnauthorized());
        mockMvc.perform(fromNewIp(post(PATH)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verify(subscriptionService, never()).subscribe(any());
    }

    @Test
    void getStatus_returnsDriverSubscription() throws Exception {
        User driver = activeUser();
        Instant end = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        when(subscriptionService.getStatus(driver.getId())).thenReturn(
                new SubscriptionResponse(UUID.randomUUID(), 2000, SubscriptionStatus.ACTIVE, true, Instant.now(), end, false));

        mockMvc.perform(authed(get(PATH), bearerFor(driver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceFcfa").value(2000))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentlyActive").value(true))
                .andExpect(jsonPath("$.currentPeriodEnd").value(end.toString()));
        verify(subscriptionService).getStatus(driver.getId());
    }

    @Test
    void subscribe_is201_withKkiapayWidgetData() throws Exception {
        User driver = activeUser();
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionService.subscribe(driver.getId())).thenReturn(new InitiatePaymentResponse(
                UUID.randomUUID(), "EKU-SUB-1", 2000, "pk_test", true, Map.of("subscriptionId", subscriptionId.toString())));

        mockMvc.perform(authed(post(PATH), bearerFor(driver)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(2000))
                .andExpect(jsonPath("$.widgetData.subscriptionId").value(subscriptionId.toString()));
        verify(subscriptionService).subscribe(driver.getId());
    }

    @Test
    void subscribe_whenAlreadyActive_is409() throws Exception {
        when(subscriptionService.subscribe(any())).thenThrow(new ConflictException("Abonnement deja actif"));

        mockMvc.perform(authed(post(PATH), bearerFor(activeUser())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/conflict"))
                .andExpect(jsonPath("$.detail").value("Abonnement deja actif"));
    }

    @Test
    void subscribe_whenKkiapayIsDown_is503() throws Exception {
        when(subscriptionService.subscribe(any())).thenThrow(new KkiapayUnavailableException("panne", null));

        mockMvc.perform(authed(post(PATH), bearerFor(activeUser())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://ekuiseo.bj/problems/upstream-unavailable"));
    }
}
