package com.byzi.api.service.subscription;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.subscription.AppleSubscriptionReportRequest;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La garde anti-desordre de {@code SubscriptionService} compare des horodatages. Les deux
 * sources n'ont pas la meme horloge :
 * <ul>
 *   <li>un rapport de l'app est date de sa <b>reception</b> par le serveur ({@code Instant.now()}) ;</li>
 *   <li>une notification Apple est datee de sa <b>signature</b> ({@code signedDate}), qui ne
 *       change pas quand Apple la relivre une heure ou un jour plus tard.</li>
 * </ul>
 * Constate en production le 2026-10-06 : l'app rapporte l'essai a 08:05:55, Apple relivre a
 * 09:06 la notification de l'achat, signee a 08:05 - et elle etait jetee comme "anterieure".
 */
@SpringBootTest
@ActiveProfiles("test")
class SubscriptionOrderingIntegrationTest {

    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private UserRepository userRepository;

    private User givenUser() {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.TRIAL)
                .build());
    }

    private static String eventId() {
        return "apple-assn:" + UUID.randomUUID();
    }

    @Test
    void anAppleNotificationRedeliveredAfterAClientReportIsStillApplied() {
        User user = givenUser();
        Instant now = Instant.now();
        subscriptionService.applyClientReportedApplePurchase(user.getId(), new AppleSubscriptionReportRequest(
                UUID.randomUUID().toString(), "dopamyn.app.premium.yearly",
                now.plus(3, ChronoUnit.DAYS), true));

        // Signee une heure AVANT le rapport, livree apres : le cas des relivraisons d'Apple.
        boolean applied = subscriptionService.applyServerNotification(user.getId(), eventId(),
                "DID_CHANGE_RENEWAL_STATUS", SubscriptionStatus.TRIAL,
                now.plus(3, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS));

        assertThat(applied)
                .as("un rapport de l'app ne doit pas rendre perimees les notifications Apple en vol")
                .isTrue();
    }

    @Test
    void anOlderAppleNotificationIsStillRejectedAfterANewerOne() {
        User user = givenUser();
        Instant now = Instant.now();
        subscriptionService.applyServerNotification(user.getId(), eventId(), "EXPIRED",
                SubscriptionStatus.EXPIRED, now, now);

        // Un renouvellement retardataire, signe AVANT l'expiration : il ne doit pas
        // redonner l'acces. C'est la raison d'etre de la garde, elle doit tenir.
        boolean applied = subscriptionService.applyServerNotification(user.getId(), eventId(), "DID_RENEW",
                SubscriptionStatus.ACTIVE, now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS));

        assertThat(applied).isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }
}
