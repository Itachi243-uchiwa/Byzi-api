package com.byzi.api.service.subscription;

import com.byzi.api.domain.SubscriptionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RevenueCatEventMapperTest {

    private final RevenueCatEventMapper mapper = new RevenueCatEventMapper();

    @ParameterizedTest
    @CsvSource({
            "INITIAL_PURCHASE,NORMAL,ACTIVE",
            "INITIAL_PURCHASE,TRIAL,TRIAL",
            "RENEWAL,NORMAL,ACTIVE",
            "PRODUCT_CHANGE,NORMAL,ACTIVE",
            "UNCANCELLATION,NORMAL,ACTIVE",
            "SUBSCRIPTION_EXTENDED,NORMAL,ACTIVE",
            "BILLING_ISSUE,NORMAL,GRACE_PERIOD",
            "EXPIRATION,NORMAL,EXPIRED"
    })
    void mapsEventTypeToStatus(String eventType, String periodType, SubscriptionStatus expected) {
        assertThat(mapper.toStatus(eventType, periodType)).contains(expected);
    }

    @Test
    void cancellationKeepsAccessUntilExpiration() {
        // Chez RevenueCat, CANCELLATION = "renouvellement automatique desactive", pas
        // "abonnement termine". Couper l'acces ici priverait le client d'un temps deja paye ;
        // c'est EXPIRATION qui clot reellement l'abonnement.
        assertThat(mapper.toStatus("CANCELLATION", "NORMAL")).contains(SubscriptionStatus.ACTIVE);
        assertThat(mapper.toStatus("CANCELLATION", "TRIAL")).contains(SubscriptionStatus.TRIAL);
    }

    @Test
    void billingIssueDoesNotExpireImmediately() {
        // Un prelevement refuse est souvent temporaire (carte expiree) : expirer tout de suite
        // ferait perdre des clients qu'Apple aurait represente avec succes quelques jours plus tard.
        assertThat(mapper.toStatus("BILLING_ISSUE", "NORMAL")).contains(SubscriptionStatus.GRACE_PERIOD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER", "SUBSCRIBER_ALIAS", "TEST", "UN_TYPE_INVENTE_PLUS_TARD"})
    void ignoresEventsUnrelatedToSubscriptionLifecycle(String eventType) {
        // Un type inconnu doit etre ignore, pas rejete : RevenueCat rejouerait sans fin un
        // webhook en erreur, et de nouveaux types apparaissent au fil des versions.
        assertThat(mapper.toStatus(eventType, "NORMAL")).isEmpty();
    }

    @Test
    void ignoresNullEventType() {
        assertThat(mapper.toStatus(null, "NORMAL")).isEmpty();
    }

    @Test
    void isCaseInsensitiveOnBothFields() {
        assertThat(mapper.toStatus("initial_purchase", "trial")).contains(SubscriptionStatus.TRIAL);
    }

    @Test
    void treatsMissingPeriodTypeAsPaidSubscription() {
        assertThat(mapper.toStatus("INITIAL_PURCHASE", null)).contains(SubscriptionStatus.ACTIVE);
    }
}
