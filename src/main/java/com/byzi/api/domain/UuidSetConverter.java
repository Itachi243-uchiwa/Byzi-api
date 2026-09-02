package com.byzi.api.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Traduit une liste d'identifiants entre le {@code Set<UUID>} manipule par le code et la
 * chaine "uuid,uuid,uuid" stockee en base (cf. {@link ScheduleDaysConverter} pour le meme
 * parti pris).
 * <p>
 * La valeur est normalisee ICI et nulle part ailleurs : triee et dedoublonnee. Deux appareils
 * qui envoient les memes ids dans un ordre different doivent produire la meme ligne, sinon la
 * synchronisation verrait une modification la ou il n'y en a aucune et les ferait rebondir
 * l'un contre l'autre indefiniment.
 * <p>
 * L'ensemble vide est ramene a {@code null} : "aucune tache liee" doit avoir une seule
 * representation en base.
 */
@Converter
public class UuidSetConverter implements AttributeConverter<Set<UUID>, String> {

    private static final String SEPARATOR = ",";

    @Override
    public String convertToDatabaseColumn(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public Set<UUID> convertToEntityAttribute(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        // LinkedHashSet : la chaine etant deja triee a l'ecriture, la reponse JSON sort dans
        // un ordre stable plutot que dans l'ordre arbitraire d'un HashSet.
        return Arrays.stream(stored.split(SEPARATOR))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
