package bj.ekuiseo.api.service;

import bj.ekuiseo.api.common.exception.TooManyRequestsException;
import bj.ekuiseo.api.service.sms.SmsGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Orchestration de l'envoi de SMS applicatifs : delegue le transport a
 * {@link SmsGateway} (implementation log ou http, voir SmsConfig) et applique
 * une limitation de debit par numero pour les demandes d'OTP (anti-abus :
 * empeche qu'un tiers ne fasse spammer de SMS un numero de tierce personne, ou
 * qu'un attaquant ne multiplie les tentatives de brute-force du code).
 */
@Service
public class SmsService {

    private static final long IDLE_ENTRY_TTL_MILLIS = 3_600_000L;

    private final SmsGateway smsGateway;
    private final int otpMaxRequests;
    private final long otpWindowMillis;
    private final ConcurrentMap<String, Deque<Long>> otpRequestHistory = new ConcurrentHashMap<>();

    public SmsService(SmsGateway smsGateway,
                       @Value("${ekuiseo.sms.otp.rate-limit.max-requests:3}") int otpMaxRequests,
                       @Value("${ekuiseo.sms.otp.rate-limit.window-minutes:10}") long otpWindowMinutes) {
        this.smsGateway = smsGateway;
        this.otpMaxRequests = otpMaxRequests;
        this.otpWindowMillis = otpWindowMinutes * 60_000L;
    }

    /**
     * Envoie un code OTP, apres verification de la limite de debit par numero
     * (regle metier n.8 : {@code ekuiseo.sms.otp.rate-limit.*}, par defaut 3
     * demandes / 10 minutes / numero).
     */
    public void sendOtp(String phone, String code) {
        assertNotRateLimited(phone);
        smsGateway.send(phone, "Ekuiseo : votre code de verification est " + code + ". Il expire dans 5 minutes. "
                + "Ne le partagez avec personne.");
    }

    /** Envoi d'une notification SMS critique (reservation confirmee, trajet annule, rappel...), sans limite de debit. */
    public void sendCritical(String phone, String message) {
        smsGateway.send(phone, message);
    }

    private void assertNotRateLimited(String phone) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = otpRequestHistory.computeIfAbsent(phone, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > otpWindowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= otpMaxRequests) {
                throw new TooManyRequestsException(
                        "Trop de demandes de code pour ce numero, reessayez dans quelques minutes.");
            }
            timestamps.addLast(now);
        }
    }

    @Scheduled(fixedRate = 600_000)
    void cleanup() {
        long now = System.currentTimeMillis();
        otpRequestHistory.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            Long last;
            synchronized (d) {
                last = d.peekLast();
            }
            return last == null || now - last > IDLE_ENTRY_TTL_MILLIS;
        });
    }
}
