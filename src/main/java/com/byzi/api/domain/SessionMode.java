package com.byzi.api.domain;

public enum SessionMode {
    /** Sortie anticipée immédiate. */
    STANDARD,
    /** Blocage strict : sortie seulement via une friction longue (maintien 3 s). */
    DEEP_FOCUS,
    /** Sortie possible, mais après une attente qui s'allonge à chaque tentative. */
    TIMEOUT
}
