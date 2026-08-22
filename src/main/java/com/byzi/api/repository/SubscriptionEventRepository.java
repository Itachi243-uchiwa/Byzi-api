package com.byzi.api.repository;

import com.byzi.api.domain.SubscriptionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, UUID> {

    boolean existsByEventId(String eventId);

    List<SubscriptionEvent> findAllByUser_IdOrderByOccurredAtDesc(UUID userId);

    /** Dernier evenement applique a ce compte : sert a rejeter les webhooks arrives en retard. */
    Optional<SubscriptionEvent> findFirstByUser_IdOrderByOccurredAtDesc(UUID userId);
}
