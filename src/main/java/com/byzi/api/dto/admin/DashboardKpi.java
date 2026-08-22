package com.byzi.api.dto.admin;

/**
 * Indicateurs du tableau de bord admin (story 09.4).
 *
 * @param totalUsers        nombre total de comptes
 * @param newUsersLast30Days inscriptions sur les 30 derniers jours
 * @param activeSubscribers  abonnes payants en cours
 * @param trialUsers         comptes actuellement en essai
 * @param gracePeriodUsers   comptes en impaye (a relancer par le support)
 * @param expiredUsers       comptes dont l'abonnement est termine
 * @param conversionRate     part des comptes sortis de l'essai qui sont devenus payants, en %
 * @param churnRate          part des comptes sortis de l'essai qui ont fini expires, en %
 */
public record DashboardKpi(
        long totalUsers,
        long newUsersLast30Days,
        long activeSubscribers,
        long trialUsers,
        long gracePeriodUsers,
        long expiredUsers,
        double conversionRate,
        double churnRate
) {
}
