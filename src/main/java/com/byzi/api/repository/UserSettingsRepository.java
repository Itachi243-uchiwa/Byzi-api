package com.byzi.api.repository;

import com.byzi.api.domain.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSettingsRepository extends JpaRepository<UserSettings, UUID> {

    /** Ressource singulière : la clé métier est l'utilisateur, jamais l'id de la ligne. */
    Optional<UserSettings> findByUser_Id(UUID userId);
}
