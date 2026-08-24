package com.byzi.api.repository;

import com.byzi.api.domain.ReferralRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralRedemptionRepository extends JpaRepository<ReferralRedemption, UUID> {

    boolean existsByReferred_Id(UUID referredId);

    /** Au plus une ligne : la contrainte d'unicite sur referred_id le garantit. */
    Optional<ReferralRedemption> findByReferred_Id(UUID referredId);

    long countByReferrer_Id(UUID referrerId);
}
