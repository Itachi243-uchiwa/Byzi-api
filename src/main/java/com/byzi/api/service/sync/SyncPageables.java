package com.byzi.api.service.sync;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Construction du Pageable des requetes de delta (MANQUE-01).
 * <p>
 * Le tri du client est volontairement ECRASE ici. Une liste ordinaire se trie selon ce qui a
 * du sens pour l'affichage (une session par date de debut, un streak par jour), mais un delta
 * se consomme dans l'ordre chronologique des modifications : c'est ce qui permet au client
 * d'appliquer les changements sequentiellement et de retenir le updatedAt du dernier element
 * traite comme point de reprise pour la synchronisation suivante.
 * <p>
 * Trier un delta autrement que par updatedAt croissant rendrait ce point de reprise faux : une
 * interruption en cours de pagination ferait manquer definitivement les modifications restees
 * en arriere.
 */
public final class SyncPageables {

    private static final String SYNC_CURSOR_PROPERTY = "updatedAt";

    private SyncPageables() {
    }

    public static Pageable forDelta(Pageable requested) {
        return PageRequest.of(
                requested.getPageNumber(),
                requested.getPageSize(),
                Sort.by(Sort.Direction.ASC, SYNC_CURSOR_PROPERTY));
    }
}
