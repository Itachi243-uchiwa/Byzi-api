package com.byzi.api.domain;

/**
 * Nature d'une regle de blocage, telle que l'app iOS la presente a l'utilisateur.
 *
 * <ul>
 *   <li>{@code FOCUS} : la regle ne s'applique que pendant une session de focus.</li>
 *   <li>{@code LIMIT} : blocage sur creneau planifie et/ou apres un quota de temps quotidien.</li>
 * </ul>
 *
 * Le serveur ne fait que stocker et synchroniser cette valeur : c'est l'app qui arme le
 * blocage au bon moment (comme pour {@code scheduleStart}/{@code scheduleEnd}).
 */
public enum RuleKind {
    FOCUS,
    LIMIT
}
