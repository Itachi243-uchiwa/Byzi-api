package com.byzi.api.dto.account;

import com.byzi.api.dto.blockrule.AppBlockRuleResponse;
import com.byzi.api.dto.session.FocusSessionResponse;
import com.byzi.api.dto.streak.StreakRecordResponse;

import java.time.Instant;
import java.util.List;

/**
 * Export complet des donnees personnelles de l'utilisateur courant (droit a la portabilite,
 * art. 20 RGPD - MANQUE-03 de l'audit backend).
 * <p>
 * Le perimetre expose est deliberement calque sur celui du reste de l'API publique
 * (FocusSessionResponse, StreakRecordResponse, AppBlockRuleResponse) : si un champ n'apparait
 * dans aucun de ces DTO, il ne sort jamais de la base - en particulier passwordHash, role, ou
 * l'id technique d'une table que l'utilisateur ne consulte jamais directement.
 */
public record AccountExportResponse(
        AccountExportProfile profile,
        List<FocusSessionResponse> focusSessions,
        List<StreakRecordResponse> streakRecords,
        List<AppBlockRuleResponse> appBlockRules,
        List<SubscriptionEventExport> subscriptionHistory,
        ReferralExport referral,
        Instant exportedAt
) {
}
