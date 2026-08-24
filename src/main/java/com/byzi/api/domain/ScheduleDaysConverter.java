package com.byzi.api.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Traduit les jours de programmation d'une regle de blocage entre le {@code Set<DayOfWeek>}
 * manipule par le code et la chaine "1,2,3,4,5" stockee en base (numeros ISO-8601 : lundi = 1,
 * dimanche = 7).
 * <p>
 * C'est ici, et nulle part ailleurs, que la valeur est normalisee : triee et dedoublonnee.
 * Deux appareils qui envoient les memes jours dans un ordre different doivent produire la
 * meme ligne en base, sinon la synchronisation verrait une modification la ou il n'y en a
 * aucune et ferait rebondir les deux appareils l'un contre l'autre indefiniment.
 * <p>
 * L'ensemble vide est ramene a {@code null}, pas a la chaine vide : "aucun jour" n'a pas de
 * sens pour une regle de blocage, et la valeur qui exprime l'absence de restriction doit
 * etre unique - deux representations du meme etat rendraient la comparaison de deltas
 * dependante de laquelle a ete ecrite.
 */
@Converter
public class ScheduleDaysConverter implements AttributeConverter<Set<DayOfWeek>, String> {

    private static final String SEPARATOR = ",";

    @Override
    public String convertToDatabaseColumn(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        return days.stream()
                .sorted(Comparator.comparingInt(DayOfWeek::getValue))
                .map(day -> String.valueOf(day.getValue()))
                .collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public Set<DayOfWeek> convertToEntityAttribute(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        // LinkedHashSet : la chaine etant deja triee a l'ecriture, l'ordre d'insertion est
        // l'ordre des jours. La reponse JSON sort donc lundi-mardi-... et non dans l'ordre
        // arbitraire d'un HashSet, ce qui rendrait les diffs de reponses illisibles.
        return Arrays.stream(stored.split(SEPARATOR))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> DayOfWeek.of(Integer.parseInt(token)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
