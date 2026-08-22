package com.buzi.api.service.admin;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calcul du streak affiche au support (story 09.3).
 * <p>
 * Teste directement, hors contexte Spring : c'est de l'arithmetique de dates, et c'est
 * exactement le genre de logique ou une erreur d'un jour passe inapercue en integration.
 */
class AdminUserServiceStreakTest {

    private final AdminUserService service =
            new AdminUserService(null, null, null, null, null, null, null);

    private final LocalDate today = LocalDate.now();

    @Test
    void noRecordsMeansNoStreak() {
        assertThat(service.currentStreak(List.of())).isZero();
    }

    @Test
    void singleDayToday() {
        assertThat(service.currentStreak(List.of(today))).isEqualTo(1);
    }

    @Test
    void countsConsecutiveDays() {
        assertThat(service.currentStreak(List.of(
                today, today.minusDays(1), today.minusDays(2), today.minusDays(3)))).isEqualTo(4);
    }

    @Test
    void stopsAtFirstGap() {
        // Le trou du jour -2 casse la serie : les jours anterieurs appartiennent a un ancien
        // streak et ne doivent pas etre additionnes.
        assertThat(service.currentStreak(List.of(
                today, today.minusDays(1), today.minusDays(3), today.minusDays(4)))).isEqualTo(2);
    }

    @Test
    void yesterdayStillCountsAsAnOngoingStreak() {
        // A 9h du matin, la journee en cours n'a pas encore de session : afficher 0 au support
        // laisserait croire que l'utilisateur a rompu sa serie.
        assertThat(service.currentStreak(List.of(today.minusDays(1), today.minusDays(2)))).isEqualTo(2);
    }

    @Test
    void streakIsBrokenWhenLastRecordIsOlderThanYesterday() {
        assertThat(service.currentStreak(List.of(today.minusDays(2), today.minusDays(3)))).isZero();
    }

    @Test
    void duplicateDayDoesNotInflateStreak() {
        // Defensif : la contrainte uk_streak_user_day l'interdit, mais si elle sautait, un
        // doublon ne doit pas faire compter deux fois la meme journee.
        assertThat(service.currentStreak(List.of(
                today, today, today.minusDays(1)))).isEqualTo(2);
    }
}
