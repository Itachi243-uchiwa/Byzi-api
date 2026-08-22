package com.buzi.api.dto.admin;

import com.buzi.api.domain.AdminAuditLog;
import com.buzi.api.domain.SubscriptionEvent;
import com.buzi.api.domain.User;

import java.time.LocalDate;
import java.util.List;

/**
 * Vue detail d'un utilisateur pour le back-office (story 09.3).
 *
 * @param currentStreak nombre de jours consecutifs avec objectif atteint, jusqu'a aujourd'hui
 * @param recentStreakDays derniers jours enregistres, du plus recent au plus ancien
 */
public record AdminUserDetail(
        User user,
        long focusSessionCount,
        int currentStreak,
        List<LocalDate> recentStreakDays,
        List<SubscriptionEvent> subscriptionHistory,
        List<AdminAuditLog> auditTrail
) {
}
