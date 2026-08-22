package com.byzi.api.security.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookAuthenticatorTest {

    private static final String SECRET = "un-secret-partage-revenuecat";

    private final WebhookAuthenticator authenticator = new WebhookAuthenticator(new WebhookProperties(SECRET));

    @Test
    void acceptsExactSecret() {
        assertThat(authenticator.isAuthorized(SECRET)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "mauvais-secret", "un-secret-partage-revenuecaT", "un-secret-partage-revenuecat "})
    void rejectsAnythingElse(String header) {
        assertThat(authenticator.isAuthorized(header)).isFalse();
    }

    @Test
    void rejectsSecretWithCorrectPrefix() {
        // Un prefixe correct ne doit pas suffire : c'est exactement ce qu'exploite une
        // attaque temporelle qui reconstitue le secret caractere par caractere.
        assertThat(authenticator.isAuthorized("un-secret-partage")).isFalse();
    }
}
