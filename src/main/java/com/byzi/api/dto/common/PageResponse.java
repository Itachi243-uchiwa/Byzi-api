package com.byzi.api.dto.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Enveloppe de pagination du contrat d'API.
 * <p>
 * Les controllers renvoyaient directement un {@code Page<T>} de Spring Data. Ce que Jackson en
 * serialise - {@code pageable}, {@code sort}, {@code numberOfElements}, {@code first},
 * {@code empty}, un {@code sort} imbrique deux fois - n'est pas un contrat : c'est le reflet
 * accidentel d'une classe interne a Spring Data, que le projet documente lui-meme comme
 * susceptible de changer d'une version a l'autre. Un client Swift genere dessus casse a la
 * premiere montee de version du backend.
 * <p>
 * Cette enveloppe expose les six seules valeurs dont un client a besoin pour paginer, sous des
 * noms que nous choisissons et que nous nous engageons a tenir. {@code last} plutot qu'un
 * calcul cote client : c'est la condition d'arret d'une boucle de synchronisation, et la
 * derniver page a le droit d'etre pleine.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
