package com.byzi.api.dto.blockrule;

import com.byzi.api.domain.RuleKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Set;

/**
 * selectionData : blob opaque encode en base64 par le client (FamilyActivitySelection
 * serialisee). Le serveur ne le decode JAMAIS - @NotBlank/@Size sont les SEULES validations
 * appliquees, volontairement, pour ne pas avoir a en comprendre le contenu. La limite de
 * taille protege contre un payload anormalement volumineux (OWASP API4:2023).
 *
 * <p>name / kind : descriptifs, poses par l'app iOS. Optionnels dans la requete pour ne pas
 * casser les clients anterieurs ; le serveur retombe sur "" et {@link RuleKind#FOCUS}.
 */
public record AppBlockRuleRequest(

        @NotBlank
        @Size(max = 200_000)
        String selectionData,

        @Size(max = 100)
        String name,

        RuleKind kind,

        @Positive
        Integer dailyLimitMinutes,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "scheduleStart doit etre au format HH:mm")
        String scheduleStart,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "scheduleEnd doit etre au format HH:mm")
        String scheduleEnd,

        /**
         * Jours ou la plage horaire s'applique (backlog 10.7). Absent ou vide = tous les
         * jours. Pas de contrainte de taille : l'enum DayOfWeek borne deja les valeurs, et un
         * Set en borne le nombre.
         */
        Set<DayOfWeek> scheduleDays,

        boolean active,

        Instant clientUpdatedAt
) {

    /**
     * Une borne sans l'autre ne decrit aucune plage : "a partir de 9h" et "jusqu'a 18h" sont
     * des regles que le client ne saurait pas armer, et que le serveur accepterait pourtant
     * en silence. Mieux vaut un 400 explicite qu'une regle inerte que l'utilisateur croira
     * active.
     * <p>
     * Une plage a cheval sur minuit (22:00 - 06:00) reste valide, et c'est voulu : c'est
     * exactement la forme d'une regle de coucher.
     */
    @AssertTrue(message = "scheduleStart et scheduleEnd doivent etre fournis ensemble")
    public boolean isScheduleRangeComplete() {
        return (scheduleStart == null) == (scheduleEnd == null);
    }

    /**
     * Des jours sans plage horaire ne signifient rien de plus que la regle elle-meme : une
     * regle active bloque deja en permanence. Les accepter laisserait croire a une
     * programmation qui n'existe pas.
     */
    @AssertTrue(message = "scheduleDays exige une plage horaire (scheduleStart/scheduleEnd)")
    public boolean isScheduleDaysBackedByARange() {
        return scheduleDays == null || scheduleDays.isEmpty() || scheduleStart != null;
    }
}
