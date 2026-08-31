package com.byzi.api.service;

import com.byzi.api.domain.User;
import com.byzi.api.domain.UserSettings;
import com.byzi.api.dto.settings.UserSettingsRequest;
import com.byzi.api.dto.settings.UserSettingsResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.repository.UserSettingsRepository;
import com.byzi.api.service.sync.ConflictResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Comme les autres ressources synchronisées, le userId vient TOUJOURS du JWT : impossible de
 * lire ou d'écrire les réglages d'un autre compte.
 * <p>
 * Ressource singulière : la ligne est créée à la volée avec les valeurs par défaut au premier
 * accès (GET comme PUT), pour que le client n'ait jamais à gérer un 404 « pas encore de
 * réglages ». La résolution de conflit est le simple last-write-wins sur updatedAt.
 */
@Service
@RequiredArgsConstructor
public class UserSettingsService {

    /** Défaut aligné sur {@code FocusGoal.defaultMinutes} côté iOS. */
    static final int DEFAULT_DAILY_GOAL_MINUTES = 25;

    private final UserSettingsRepository userSettingsRepository;
    private final UserRepository userRepository;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    @Transactional
    public UserSettingsResponse getOrCreate(UUID userId) {
        return toResponse(
                userSettingsRepository.findByUser_Id(userId)
                        .orElseGet(() -> userSettingsRepository.save(newRow(userId, DEFAULT_DAILY_GOAL_MINUTES)))
        );
    }

    /**
     * Première écriture : la ligne est créée directement avec la valeur du client, il n'y a
     * aucun état antérieur avec qui entrer en conflit. Sur une ligne existante, last-write-wins
     * sur updatedAt — sans quoi le tout premier PUT après un GET (qui a créé la ligne avec
     * l'horloge serveur) serait toujours rejeté comme « périmé ».
     */
    @Transactional
    public UserSettingsResponse upsert(UUID userId, UserSettingsRequest request) {
        Optional<UserSettings> existing = userSettingsRepository.findByUser_Id(userId);

        UserSettings settings;
        if (existing.isPresent()) {
            settings = existing.get();
            if (!conflictResolutionStrategy.shouldApplyIncoming(request.clientUpdatedAt(), settings.getUpdatedAt())) {
                return toResponse(settings);
            }
        } else {
            settings = newRow(userId, request.dailyGoalMinutes());
        }

        settings.setDailyGoalMinutes(request.dailyGoalMinutes());
        return toResponse(userSettingsRepository.save(settings));
    }

    private UserSettings newRow(UUID userId, int dailyGoalMinutes) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));
        return UserSettings.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .dailyGoalMinutes(dailyGoalMinutes)
                .build();
    }

    private UserSettingsResponse toResponse(UserSettings settings) {
        return new UserSettingsResponse(
                settings.getDailyGoalMinutes(),
                settings.getCreatedAt(),
                settings.getUpdatedAt()
        );
    }
}
