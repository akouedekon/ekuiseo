package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.ConflictException;
import bj.ekuiseo.api.dto.payout.PayoutBatchResultResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V25 : le lot de reversement hebdomadaire se constitue seul (acteur systeme), et se tait quand il est desactive ou verrouille. */
class PayoutSchedulerTest {

    private final PayoutService payoutService = mock(PayoutService.class);

    @Test
    void runNow_buildsTheWeeklyBatch_asTheSystem() {
        PayoutBatchResultResponse result = new PayoutBatchResultResponse(2, 9000, List.of(), List.of());
        when(payoutService.runWeeklyBatch(isNull())).thenReturn(result);

        assertThat(new PayoutScheduler(payoutService, true).runNow()).isSameAs(result);
        verify(payoutService).runWeeklyBatch(isNull());
    }

    @Test
    void runNow_doesNothingWhenDisabled() {
        assertThat(new PayoutScheduler(payoutService, false).runNow()).isNull();
        verify(payoutService, never()).runWeeklyBatch(isNull());
    }

    @Test
    void runNow_absorbsALockConflict_andAnyFailure() {
        when(payoutService.runWeeklyBatch(isNull()))
                .thenThrow(new ConflictException("Un lot de reversement est deja en cours de constitution"))
                .thenThrow(new IllegalStateException("base indisponible"));

        PayoutScheduler scheduler = new PayoutScheduler(payoutService, true);
        assertThat(scheduler.runNow()).isNull();
        assertThat(scheduler.runNow()).isNull();
    }
}
