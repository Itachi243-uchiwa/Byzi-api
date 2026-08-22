package com.byzi.api.dto.blockrule;

import java.time.Instant;
import java.util.UUID;

/**
 * @param active    choix de l'utilisateur : regle desactivee mais conservee.
 * @param deletedAt tombstone : la regle n'existe plus du tout. Non nul uniquement dans les
 *                  reponses du delta de synchronisation (parametre updatedSince). Les deux
 *                  champs sont independants et ne doivent pas etre confondus cote client.
 */
public record AppBlockRuleResponse(
        UUID id,
        String selectionData,
        Integer dailyLimitMinutes,
        String scheduleStart,
        String scheduleEnd,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
