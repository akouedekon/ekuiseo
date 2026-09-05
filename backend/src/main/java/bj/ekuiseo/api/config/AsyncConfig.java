package bj.ekuiseo.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Executeur dedie a l'ecriture asynchrone des traces de recherche
 * ({@code SearchEventService#record}, annote {@code @Async("searchEventExecutor")}).
 *
 * <p>Petit et borne a dessein : une recherche ne doit jamais attendre sa trace. Si la
 * file est pleine (base lente, rafale), la trace est abandonnee et journalisee -
 * jamais executee dans le fil de la requete (ce que ferait la politique par defaut
 * CallerRunsPolicy), et jamais bloquante. Perdre quelques evenements statistiques
 * sous forte charge est acceptable ; ralentir la recherche ne l'est pas.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String SEARCH_EVENT_EXECUTOR = "searchEventExecutor";
    public static final String REFUND_EXECUTOR = "refundExecutor";
    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    /**
     * Envoi des notifications sortantes (e-mail, SMS) apres validation de la transaction
     * metier (NotificationDispatcher). Une file pleine abandonne l envoi et le journalise :
     * la notification in-app, elle, est deja en base.
     */
    @Bean(name = NOTIFICATION_EXECUTOR)
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("notifications-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1_000);
        executor.setRejectedExecutionHandler((runnable, pool) ->
                log.warn("Notification sortante abandonnee : file d envoi pleine ({} en attente)", pool.getQueue().size()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /** Execution des remboursements Kkiapay apres validation de la transaction metier (RefundService). */
    @Bean(name = REFUND_EXECUTOR)
    public Executor refundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("refunds-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler((runnable, pool) ->
                log.warn("Remboursement differe a la reprise planifiee : file pleine ({} en attente)", pool.getQueue().size()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }


    @Bean(name = SEARCH_EVENT_EXECUTOR)
    public Executor searchEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("search-events-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1_000);
        executor.setRejectedExecutionHandler((runnable, pool) ->
                log.warn("Trace de recherche abandonnee : file d'ecriture pleine ({} en attente)", pool.getQueue().size()));
        // A l'arret : on laisse au plus 5 s aux traces en attente, puis on coupe.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
