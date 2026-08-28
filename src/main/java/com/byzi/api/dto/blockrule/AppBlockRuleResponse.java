package com.byzi.api.dto.blockrule;

import com.byzi.api.domain.RuleKind;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * @param name         libelle utilisateur ("" si jamais renseigne).
 * @param kind         FOCUS ou LIMIT (FOCUS par defaut pour les regles anterieures a V8).
 * @param scheduleDays jours ou la plage horaire s'applique (backlog 10.7). Absent = tous les
 *                     jours. Toujours trie du lundi au dimanche.
 * @param active       choix de l'utilisateur : regle desactivee mais conservee.
 * @param deletedAt    tombstone : la regle n'existe plus du tout. Non nul uniquement dans les
 *                     reponses du delta de synchronisation (parametre updatedSince). Les deux
 *                     champs sont independants et ne doivent pas etre confondus cote client.
 */
public record AppBlockRuleResponse(
        UUID id,
        String selectionData,
        String name,
        RuleKind kind,
        Integer dailyLimitMinutes,
        String scheduleStart,
        String scheduleEnd,
        Set<DayOfWeek> scheduleDays,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
