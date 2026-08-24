package com.byzi.api.domain;

import java.util.Arrays;

/**
 * Roles applicatifs. USER couvre l'app iOS ; les trois autres couvrent le back-office
 * (story 17.4 - gestion fine des roles admin).
 * <p>
 * La separation repond a un principe simple : personne ne doit disposer en permanence de
 * droits dont son travail quotidien n'a pas besoin. Un charge de support consulte des comptes
 * toute la journee et n'a aucune raison de pouvoir en supprimer un ; un profil finance
 * prolonge des essais et acte des remboursements sans avoir a lire les tickets.
 * <p>
 * La hierarchie est declaree dans {@code SecurityConfig#roleHierarchy} : ADMIN implique les
 * deux roles specialises, si bien qu'une regle {@code hasRole('ADMIN_FINANCE')} est satisfaite
 * par un ADMIN sans avoir a enumerer les cas.
 */
public enum Role {

    /** Utilisateur de l'app iOS. Aucun acces au back-office. */
    USER,

    /** Administrateur complet : tout le back-office, y compris la suppression de comptes. */
    ADMIN,

    /** Support : consultation des comptes et traitement des tickets. Aucune action financiere. */
    ADMIN_SUPPORT,

    /** Finance : gestes commerciaux, remboursements et exports de reporting. */
    ADMIN_FINANCE;

    public boolean isAdmin() {
        return this != USER;
    }

    /**
     * Noms des roles autorises a entrer dans le back-office, au format attendu par
     * {@code hasAnyRole} (sans le prefixe ROLE_).
     * <p>
     * Derives de l'enum plutot qu'ecrits en dur dans la chaine de securite : ajouter un role
     * d'administration sans penser a mettre la liste a jour lui fermerait la porte d'entree,
     * et le symptome - "mon nouveau role ne peut pas se connecter" - n'oriente pas vers la
     * chaine de securite.
     */
    public static String[] adminRoleNames() {
        return Arrays.stream(values())
                .filter(Role::isAdmin)
                .map(Enum::name)
                .toArray(String[]::new);
    }
}
