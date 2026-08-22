package com.byzi.api.repository;

import com.byzi.api.domain.FocusSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Toutes les methodes qui renvoient ou modifient des donnees portent le userId dans leur
 * signature : il ne doit exister AUCUN chemin permettant de lire ou d'ecrire une session par
 * son seul id (defense de fond contre l'IDOR, OWASP A01). Les methodes heritees de
 * JpaRepository qui ignorent le proprietaire (findById, deleteById) ne sont pas utilisees par
 * les services metier - seul existsById l'est, et uniquement pour savoir si une cle primaire
 * est libre, ce qui ne divulgue aucune donnee.
 */
public interface FocusSessionRepository extends JpaRepository<FocusSession, UUID> {

    Optional<FocusSession> findByIdAndUser_Id(UUID id, UUID userId);

    Page<FocusSession> findAllByUser_IdOrderByStartedAtDesc(UUID userId, Pageable pageable);

    long deleteByIdAndUser_Id(UUID id, UUID userId);

    long countByUser_Id(UUID userId);
}
