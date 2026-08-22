package com.byzi.api.service;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.account.MeResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * HAUT-01 de l'audit backend : couvre le calcul de hasActiveAccess pour chaque combinaison
 * statut/date, isolement du reste de la pile HTTP (le parcours bout-en-bout est couvert par
 * MeIntegrationTest).
 */
@ExtendWith(MockitoExtension.class)
class AccountProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    private AccountProfileService service;

    @BeforeEach
    void setUp() {
        service = new AccountProfileService(userRepository);
    }

    private User userWith(SubscriptionStatus status, Instant expiresAt) {
        return User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub")
                .email("user@byzi.app")
                .role(Role.USER)
                .subscriptionStatus(status)
                .subscriptionExpiresAt(expiresAt)
                .build();
    }

    static Stream<Arguments> accessCases() {
        Instant future = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant past = Instant.now().minus(10, ChronoUnit.DAYS);
        return Stream.of(
                // ACTIVE / TRIAL : acces seulement si une date future existe.
                Arguments.of(SubscriptionStatus.TRIAL, future, true),
                Arguments.of(SubscriptionStatus.TRIAL, past, false),
                Arguments.of(SubscriptionStatus.TRIAL, null, false),
                Arguments.of(SubscriptionStatus.ACTIVE, future, true),
                Arguments.of(SubscriptionStatus.ACTIVE, past, false),
                Arguments.of(SubscriptionStatus.ACTIVE, null, false),
                // GRACE_PERIOD : acces inconditionnel, la date n'entre pas en ligne de compte.
                Arguments.of(SubscriptionStatus.GRACE_PERIOD, future, true),
                Arguments.of(SubscriptionStatus.GRACE_PERIOD, past, true),
                Arguments.of(SubscriptionStatus.GRACE_PERIOD, null, true),
                // EXPIRED : jamais d'acces, meme avec une date future incoherente en base.
                Arguments.of(SubscriptionStatus.EXPIRED, future, false),
                Arguments.of(SubscriptionStatus.EXPIRED, past, false),
                Arguments.of(SubscriptionStatus.EXPIRED, null, false)
        );
    }

    @ParameterizedTest(name = "{0} / expiresAt={1} -> hasActiveAccess={2}")
    @MethodSource("accessCases")
    void computesAccessFromStatusAndExpiration(SubscriptionStatus status, Instant expiresAt, boolean expectedAccess) {
        User user = userWith(status, expiresAt);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        MeResponse response = service.currentProfile(user.getId());

        assertThat(response.hasActiveAccess()).isEqualTo(expectedAccess);
        assertThat(response.subscriptionStatus()).isEqualTo(status);
        assertThat(response.subscriptionExpiresAt()).isEqualTo(expiresAt);
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.email()).isEqualTo("user@byzi.app");
    }

    @Test
    void throwsNotFoundWhenAccountDoesNotExist() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.currentProfile(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
