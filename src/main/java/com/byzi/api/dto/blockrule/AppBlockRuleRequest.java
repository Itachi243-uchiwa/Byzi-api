package com.byzi.api.dto.blockrule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * selectionData : blob opaque encode en base64 par le client (FamilyActivitySelection
 * serialisee). Le serveur ne le decode JAMAIS - @NotBlank/@Size sont les SEULES validations
 * appliquees, volontairement, pour ne pas avoir a en comprendre le contenu. La limite de
 * taille protege contre un payload anormalement volumineux (OWASP API4:2023).
 */
public record AppBlockRuleRequest(

        @NotBlank
        @Size(max = 200_000)
        String selectionData,

        @Positive
        Integer dailyLimitMinutes,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "scheduleStart doit etre au format HH:mm")
        String scheduleStart,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "scheduleEnd doit etre au format HH:mm")
        String scheduleEnd,

        boolean active,

        Instant clientUpdatedAt
) {
}