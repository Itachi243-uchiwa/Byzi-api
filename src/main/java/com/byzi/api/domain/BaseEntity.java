package com.byzi.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * @CreatedDate / @LastModifiedDate ne font rien sans AuditingEntityListener : c'est ce
 * listener qui renseigne les deux colonnes avant l'insert/update. Sans lui, created_at et
 * updated_at partent a null et violent la contrainte "not null" du schema.
 * <p>
 * updatedAt sert aussi d'horloge serveur a la resolution de conflits de synchronisation
 * (cf. ConflictResolutionStrategy) : c'est la valeur comparee au clientUpdatedAt envoye
 * par l'app iOS.
 */
@Data
@EqualsAndHashCode(of = "id")
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@SuperBuilder
@NoArgsConstructor
public class BaseEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
