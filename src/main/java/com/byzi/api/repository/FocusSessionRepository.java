package com.byzi.api.repository;

import com.byzi.api.domain.FocusSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Toutes les methodes qui renvoient ou modifient des donnees portent le userId dans leur
 * signature : il ne doit exister AUCUN chemin permettant de lire ou d'ecrire une session par
 * son seul id (defense de fond contre l'IDOR, OWASP A01). Les methodes heritees de
 * JpaRepository qui ignorent le proprietaire (findById, deleteById) ne sont pas utilisees par
 * les services metier - seul existsById l'est, et uniquement pour savoir si une cle primaire
 * est libre, ce qui ne divulgue aucune donnee.
 * <p>
 * Depuis l'introduction des tombstones (V5), deux familles de methodes coexistent et ne
 * doivent pas etre confondues :
 * <ul>
 *   <li>{@code ...DeletedAtIsNull} : ce que le client doit voir. Utilise par les GET
 *       unitaires et les listes ordinaires.</li>
 *   <li>{@code findAllByUser_IdAndUpdatedAtGreaterThanEqual} : le delta de synchronisation,
 *       qui inclut DELIBEREMENT les lignes supprimees - c'est tout l'interet du tombstone.</li>
 * </ul>
 */
public interface FocusSessionRepository extends JpaRepository<FocusSession, UUID> {

    /**
     * Inclut les lignes supprimees. Reserve a l'upsert, qui doit pouvoir constater qu'une
     * ressource a ete supprimee pour appliquer la regle de resurrection (cf. FocusSessionService).
     */
    Optional<FocusSession> findByIdAndUser_Id(UUID id, UUID userId);

    /** Vue client : une session supprimee est introuvable, exactement comme une session inexistante. */
    Optional<FocusSession> findByIdAndUser_IdAndDeletedAtIsNull(UUID id, UUID userId);

    Page<FocusSession> findAllByUser_IdAndDeletedAtIsNullOrderByStartedAtDesc(UUID userId, Pageable pageable);

    /**
     * Delta de synchronisation (MANQUE-01). Renvoie tout ce qui a change depuis updatedSince,
     * <b>tombstones compris</b> : sans eux, un appareil hors ligne au moment d'une suppression
     * ne l'apprendrait jamais. Le tri est impose par le Pageable construit dans le service.
     */
    Page<FocusSession> findAllByUser_IdAndUpdatedAtGreaterThanEqual(UUID userId, Instant updatedSince, Pageable pageable);

    /**
     * Suppression PHYSIQUE. N'est plus utilisee par la synchronisation, qui pose desormais un
     * tombstone : conservee uniquement pour un besoin administratif eventuel. Ne pas l'appeler
     * depuis un endpoint client, sous peine de reintroduire le probleme de resurrection.
     */
    long deleteByIdAndUser_Id(UUID id, UUID userId);

    /** Compteur journalise a la suppression de compte : inclut volontairement les tombstones. */
    long countByUser_Id(UUID userId);
}
