package com.byzi.api.service;

import com.byzi.api.domain.FocusSession;
import com.byzi.api.domain.Role;
import com.byzi.api.domain.SessionMode;
import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.account.AccountExportResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.AppBlockRuleMapper;
import com.byzi.api.mapper.TodoTaskMapper;
import com.byzi.api.mapper.WeeklyObjectiveMapper;
import com.byzi.api.mapper.FocusSessionMapper;
import com.byzi.api.mapper.StreakRecordMapper;
import com.byzi.api.repository.AppBlockRuleRepository;
import com.byzi.api.repository.TodoTaskRepository;
import com.byzi.api.repository.WeeklyObjectiveRepository;
import com.byzi.api.repository.FocusSessionRepository;
import com.byzi.api.repository.StreakRecordRepository;
import com.byzi.api.repository.ReferralRedemptionRepository;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * MANQUE-03 de l'audit backend (droit a la portabilite, art. 20 RGPD). Verifie trois choses :
 * l'export ne fuite aucune donnee interne (passwordHash/role n'existent tout simplement pas
 * dans les DTO utilises, cf. AccountExportProfile), le parcours pagine visite bien toutes les
 * pages d'un depot et pas seulement la premiere (la clause de volumetrie de l'audit), et un
 * compte inconnu declenche un 404 plutot qu'un export vide.
 */
@ExtendWith(MockitoExtension.class)
class AccountExportServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private FocusSessionRepository focusSessionRepository;
    @Mock
    private StreakRecordRepository streakRecordRepository;
    @Mock
    private AppBlockRuleRepository appBlockRuleRepository;
    @Mock
    private TodoTaskRepository todoTaskRepository;
    @Mock
    private WeeklyObjectiveRepository weeklyObjectiveRepository;
    @Mock
    private SubscriptionEventRepository subscriptionEventRepository;
    @Mock
    private ReferralRedemptionRepository referralRedemptionRepository;

    private AccountExportService service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountExportService(
                userRepository, focusSessionRepository, streakRecordRepository, appBlockRuleRepository,
                todoTaskRepository, weeklyObjectiveRepository, subscriptionEventRepository,
                referralRedemptionRepository, new FocusSessionMapper(), new StreakRecordMapper(),
                new AppBlockRuleMapper(), new TodoTaskMapper(), new WeeklyObjectiveMapper());

        user = User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-export")
                .email("export@byzi.app")
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .subscriptionExpiresAt(Instant.now().plus(Duration.ofDays(5)))
                .build();
        // lenient : le compte de reference sert a la majorite des tests, mais pas a celui qui
        // verifie le cas "compte inconnu". Sans cela, les strict stubs de Mockito font echouer
        // ce test pour un stub inutilise, ce qui n'apprend rien sur le comportement teste.
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void throwsNotFoundForUnknownAccount() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.export(unknown)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void profileNeverExposesPasswordHashOrRole() {
        stubEmptyCollections();

        AccountExportResponse export = service.export(user.getId());

        // AccountExportProfile n'a tout simplement pas de champ passwordHash/role : ce test
        // fige le contrat (userId/appleSub/email/statut), toute regression qui ajouterait un
        // champ interne au DTO devra passer par une revue explicite de ce test.
        assertThat(export.profile().userId()).isEqualTo(user.getId());
        assertThat(export.profile().appleSub()).isEqualTo("apple-sub-export");
        assertThat(export.profile().email()).isEqualTo("export@byzi.app");
        assertThat(export.profile().subscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void walksEveryPageOfFocusSessions() {
        FocusSession first = focusSession();
        FocusSession second = focusSession();

        // Deux "pages" fabriquees a la main (independantes de EXPORT_PAGE_SIZE, qui reste
        // prive) : la premiere annonce qu'elle n'est pas la derniere, ce qui force le service a
        // rappeler le repository pour la suivante - exactement le comportement attendu de
        // loadAll() face a un utilisateur dont les donnees depassent une page.
        when(focusSessionRepository.findAllByUser_IdAndDeletedAtIsNullOrderByStartedAtDesc(eq(user.getId()), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable requested = invocation.getArgument(1);
                    if (requested.getPageNumber() == 0) {
                        return new PageImpl<>(List.of(first), PageRequest.of(0, 1), 2);
                    }
                    return new PageImpl<>(List.of(second), PageRequest.of(1, 1), 2);
                });
        stubEmptyStreaksRulesAndSubscriptionEvents();

        AccountExportResponse export = service.export(user.getId());

        assertThat(export.focusSessions()).hasSize(2);
        assertThat(export.focusSessions()).extracting(r -> r.id())
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void includesSubscriptionHistoryWithoutInternalIdentifiers() {
        stubEmptyCollections();
        SubscriptionEvent event = SubscriptionEvent.builder()
                .id(UUID.randomUUID())
                .user(user)
                .eventId("revenuecat-event-id")
                .eventType("INITIAL_PURCHASE")
                .resultingStatus(SubscriptionStatus.ACTIVE)
                .expiresAt(Instant.now().plus(Duration.ofDays(30)))
                .occurredAt(Instant.now())
                .receivedAt(Instant.now())
                .build();
        when(subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(user.getId()))
                .thenReturn(List.of(event));

        AccountExportResponse export = service.export(user.getId());

        assertThat(export.subscriptionHistory()).hasSize(1);
        assertThat(export.subscriptionHistory().get(0).eventType()).isEqualTo("INITIAL_PURCHASE");
        assertThat(export.subscriptionHistory().get(0).resultingStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        // SubscriptionEventExport n'a pas de champ id/eventId : rien de plus a asserter, le
        // contrat du DTO garantit deja qu'ils ne peuvent pas fuiter dans la reponse.
    }

    private void stubEmptyCollections() {
        when(focusSessionRepository.findAllByUser_IdAndDeletedAtIsNullOrderByStartedAtDesc(eq(user.getId()), any(Pageable.class)))
                .thenReturn(Page.empty());
        stubEmptyStreaksRulesAndSubscriptionEvents();
    }

    private void stubEmptyStreaksRulesAndSubscriptionEvents() {
        when(streakRecordRepository.findAllByUser_IdAndDeletedAtIsNullOrderByDayDesc(eq(user.getId()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(appBlockRuleRepository.findAllByUser_IdAndDeletedAtIsNull(eq(user.getId()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(todoTaskRepository.findAllByUser_IdAndDeletedAtIsNull(eq(user.getId()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(weeklyObjectiveRepository.findAllByUser_IdAndDeletedAtIsNull(eq(user.getId()), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(user.getId()))
                .thenReturn(List.of());
    }

    private FocusSession focusSession() {
        return FocusSession.builder()
                .id(UUID.randomUUID())
                .user(user)
                .startedAt(Instant.now())
                .plannedDurationSeconds(1500)
                .mode(SessionMode.STANDARD)
                .build();
    }
}
